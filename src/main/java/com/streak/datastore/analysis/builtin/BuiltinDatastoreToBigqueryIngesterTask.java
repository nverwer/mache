/*
 * Copyright 2012 Rewardly Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.streak.datastore.analysis.builtin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.api.client.googleapis.extensions.appengine.auth.oauth2.AppIdentityCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson.JacksonFactory;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.Bigquery.Jobs.Insert;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfiguration;
import com.google.api.services.bigquery.model.JobConfigurationLoad;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.TableReference;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.CompositeFilter;
import com.google.appengine.api.datastore.Query.CompositeFilterOperator;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.Query.FilterPredicate;
import com.google.appengine.api.datastore.Text;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.streak.logging.utils.AnalysisConstants;
import com.streak.logging.utils.AnalysisUtility;

@SuppressWarnings("serial")
public class BuiltinDatastoreToBigqueryIngesterTask extends HttpServlet {
	private static final int MILLIS_TO_ENQUEUE = 180000; // 3 min
	private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	private static final JsonFactory JSON_FACTORY = new JacksonFactory();

	private static final String BUILTIN_DATASTORE_TO_BIGQUERY_INGESTOR_TASK_PATH = "/builtinDatastoreToBigqueryIngestorTask";

	private static final Logger log = Logger.getLogger("bqlogging");

	private static final long MAX_AGE_BACKUP_NOT_FOUND_MS = 900000; // 15 min

	public static void enqueueTask(String baseUrl, BuiltinDatastoreExportConfiguration exporterConfig, long timestamp) {
		enqueueTask(baseUrl, exporterConfig, timestamp, 0);
	}

	private static void enqueueTask(String baseUrl, BuiltinDatastoreExportConfiguration exporterConfig, long timestamp, long countdownMillis) {
		TaskOptions t = TaskOptions.Builder.withUrl(baseUrl + BUILTIN_DATASTORE_TO_BIGQUERY_INGESTOR_TASK_PATH);
		t.param(AnalysisConstants.TIMESTAMP_PARAM, Long.toString(timestamp));
		t.param(AnalysisConstants.BUILTIN_DATASTORE_EXPORT_CONFIG, exporterConfig.getClass().getName());

		t.method(Method.GET);
		if (countdownMillis > 0) {
			t.countdownMillis(countdownMillis);
		}
		Queue queue;
		if (!AnalysisUtility.areParametersValid(exporterConfig.getQueueName())) {
			queue = QueueFactory.getDefaultQueue();
		}
		else {
			queue = QueueFactory.getQueue(exporterConfig.getQueueName());
		}
		queue.add(t);
	}


	@Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		resp.setContentType("application/json");

		String timestampStr = req.getParameter(AnalysisConstants.TIMESTAMP_PARAM);
		long timestamp = 0;
		if (AnalysisUtility.areParametersValid(timestampStr)) {
			try {
				timestamp = Long.parseLong(timestampStr);
			}
			catch (Exception e) {
				// leave it at default value
			}
		}
		if (timestamp == 0) {
			log.severe("Missing required param: " + AnalysisConstants.TIMESTAMP_PARAM);
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}

		String builtinDatastoreExportConfig = req.getParameter(AnalysisConstants.BUILTIN_DATASTORE_EXPORT_CONFIG);
		if (!AnalysisUtility.areParametersValid(builtinDatastoreExportConfig)) {
			log.severe("Missing required param: " + AnalysisConstants.BUILTIN_DATASTORE_EXPORT_CONFIG);
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}

    // Instantiate the export config
    BuiltinDatastoreExportConfiguration exporterConfig = AnalysisUtility.instantiateDatastoreExportConfig(builtinDatastoreExportConfig);

    if (timestamp - System.currentTimeMillis() > MAX_AGE_BACKUP_NOT_FOUND_MS) {
      log.severe("Cannot find backup after retrying 15 minutes: "+exporterConfig.getBucketName()+"; builtinDatastoreExportConfig: "+builtinDatastoreExportConfig);
      resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

		String gsHandleOfBackup = checkAndGetCompletedBackupGSHandle(AnalysisUtility.getPreBackupName(timestamp, exporterConfig.getBackupNamePrefix()));
		if (gsHandleOfBackup == null) {
		  String baseUrl = AnalysisUtility.getRequestBaseName(req);
			log.severe("gsHandleOfBackup: null; expected: "+exporterConfig.getBucketName()+"; builtinDatastoreExportConfig: "+builtinDatastoreExportConfig);
			log.info("backup incomplete, retrying "+baseUrl+" in " + MILLIS_TO_ENQUEUE + " millis");
			resp.getWriter().println(AnalysisUtility.successJson("backup incomplete, retrying in " + MILLIS_TO_ENQUEUE + " millis"));
			enqueueTask(baseUrl, exporterConfig, timestamp, MILLIS_TO_ENQUEUE);
			return;
		}
		log.info("backup complete, starting bigquery ingestion");
		log.info("gsHandleOfBackup: " + gsHandleOfBackup);

		AppIdentityCredential credential = new AppIdentityCredential(AnalysisConstants.SCOPES);

		Bigquery bigquery = new Bigquery.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential).setApplicationName("Streak Logs").build();

		String datatableSuffix = "";
		if (exporterConfig.appendTimestampToDatatables()) {
			datatableSuffix = Long.toString(timestamp);
		}
		else {
			datatableSuffix = "";
		}

		if (!exporterConfig.appendTimestampToDatatables()) {
			// we aren't appending the timestamps so delete the old tables if they exist
			for (String kind : exporterConfig.getEntityKindsToExport()) {
				boolean found = true;
				try {
					bigquery.tables().get(exporterConfig.getBigqueryProjectId(), exporterConfig.getBigqueryDatasetId(), kind).execute();
				}
				catch (IOException e) {
					// table not found so don't need to do anything
					found = false;
				}

				if (found) {
				  log.info("Deleting old BigQuery table for kind: " + kind);
					bigquery.tables().delete(exporterConfig.getBigqueryProjectId(), exporterConfig.getBigqueryDatasetId(), kind).execute();
				}
			}
		}

		// now create the ingestion
		for (String kind : exporterConfig.getEntityKindsToExport()) {
			String gsUrl = convertHandleToUrl(gsHandleOfBackup, kind);
			log.info("Ingest into BigQuery, gsUrl: " + gsUrl + ", kind: " + kind);

			Job job = new Job();
			JobConfiguration config = new JobConfiguration();
			JobConfigurationLoad loadConfig = new JobConfigurationLoad();

			loadConfig.setSourceUris(Arrays.asList(gsUrl));
			loadConfig.set("sourceFormat", "DATASTORE_BACKUP");
			loadConfig.set("allowQuotedNewlines", true);

			TableReference table = new TableReference();
			table.setProjectId(exporterConfig.getBigqueryProjectId());
			table.setDatasetId(exporterConfig.getBigqueryDatasetId());
			table.setTableId(kind + datatableSuffix);
			loadConfig.setDestinationTable(table);

			config.setLoad(loadConfig);
			job.setConfiguration(config);
			Insert insert = bigquery.jobs().insert(exporterConfig.getBigqueryProjectId(), job);

			JobReference jr = insert.execute().getJobReference();
			log.info("Ingest job for BigQuery, gsUrl: " + gsUrl + ", kind: " + kind + ", JobId: " + jr.getJobId());
		}
	}

	private String convertHandleToUrl(String gsHandleOfBackup, String kind) {
		String retVal = gsHandleOfBackup.replaceAll("/gs/", Matcher.quoteReplacement("gs://"));
		retVal = retVal.replaceAll("backup_info", kind + ".backup_info");
		return retVal;
	}

	private String checkAndGetCompletedBackupGSHandle(String backupName) throws IOException {
		log.info("checkAndGetCompletedBackupGSHandle, backupName: " + backupName);

		DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

		Query q = new Query("_AE_Backup_Information");

		// For some reason the datastore admin code appends the date to the backup name even when creating programatically,
		// so test for greater than or equal to and then take the first result.
		FilterPredicate greater = new FilterPredicate("name", FilterOperator.GREATER_THAN_OR_EQUAL, backupName);
		FilterPredicate less = new FilterPredicate("name", FilterOperator.LESS_THAN_OR_EQUAL, backupName + "Z");
		List<Query.Filter> filters = new ArrayList<Query.Filter>();
		filters.add(greater);
		filters.add(less);
		CompositeFilter comp = new CompositeFilter(CompositeFilterOperator.AND, filters);
		q.setFilter(comp);

		try {
  		PreparedQuery pq = datastore.prepare(q);
  		List<Entity> results = pq.asList(FetchOptions.Builder.withLimit(1));
  		if (results.size() != 1 || !results.get(0).getProperty("name").toString().contains(backupName)) {
  		  String message = "BuiltinDatatoreToBigqueryIngesterTask: can't find backupName: " + backupName;
  		  String resultsNames = "Results names: [";
  		  for (int i = 0; i < results.size(); ++i) {
  		    resultsNames += results.get(i).getProperty("name").toString();
  		  }
  		  resultsNames +=  "]";
  			log.severe(message);
  			log.severe(resultsNames);
  			return null;
  		}
  		Entity result = results.get(0);

  		Object completion = result.getProperty("complete_time");
  		Object gs_handle_obj = result.getProperty("gs_handle");
  		if (gs_handle_obj == null) {
  		  log.severe("Backup has no gs handle: "+result.toString());
  			return null;
  		}
  		log.info("gs handle object: " + gs_handle_obj.toString());
  		log.info("gs handle obj type: " + gs_handle_obj.getClass().toString());

  		String gs_handle = null;
  		if (gs_handle_obj instanceof String) {
  			gs_handle = (String) gs_handle_obj;
  		}
  		else if (gs_handle_obj instanceof Text) {
  			gs_handle = ((Text)gs_handle_obj).getValue();
  		}

  		String keyResult = null;
  		if (completion != null) {
  			keyResult = KeyFactory.keyToString(result.getKey());
  		}

  		log.info("checkAndGetCompletedBackupGSHandle, result: " + result);
  		log.info("checkAndGetCompletedBackupGSHandle, complete_time: " + completion);
  		log.info("checkAndGetCompletedBackupGSHandle, keyResult: " + keyResult);
  		log.info("checkAndGetCompletedBackupGSHandle, gs_handle: " + gs_handle);

  		return gs_handle;
		} catch (Exception ex) {
		  log.severe("checkAndGetCompletedBackupGSHandle encountered a "+ex.getClass().getName()+" for "+backupName+": "+ex.getMessage());
		  return null;
		}
	}
}
