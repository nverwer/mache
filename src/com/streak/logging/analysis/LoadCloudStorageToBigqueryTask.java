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

package com.streak.logging.analysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.api.client.googleapis.extensions.appengine.auth.oauth2.AppIdentityCredential;
import com.google.api.client.http.HttpRequestFactory;
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
import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.appengine.api.blobstore.BlobKey;
import com.google.appengine.api.blobstore.BlobstoreService;
import com.google.appengine.api.blobstore.BlobstoreServiceFactory;
import com.google.appengine.api.files.AppEngineFile;
import com.google.appengine.api.files.FileReadChannel;
import com.google.appengine.api.files.FileService;
import com.google.appengine.api.files.FileServiceFactory;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions.Builder;
import com.google.appengine.api.taskqueue.TaskOptions.Method;

public class LoadCloudStorageToBigqueryTask extends HttpServlet {
	private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	private static final JsonFactory JSON_FACTORY = new JacksonFactory();
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		resp.setContentType("text/plain");
		
		MemcacheService memcache = MemcacheServiceFactory.getMemcacheService(AnalysisConstants.MEMCACHE_NAMESPACE); 
		Long nextBigQueryJobTime = 
				(Long) memcache.increment(
						AnalysisConstants.LAST_BIGQUERY_JOB_TIME, AnalysisConstants.LOAD_DELAY_MS, System.currentTimeMillis());
		
		long currentTime = System.currentTimeMillis();
		
		// The task queue has waited a long time to run us. Go ahead and reset the last job time
		// to prevent a race.
		if (currentTime > nextBigQueryJobTime + AnalysisConstants.LOAD_DELAY_MS / 2) {
			memcache.put(AnalysisConstants.LAST_BIGQUERY_JOB_TIME, currentTime);
			nextBigQueryJobTime = currentTime + AnalysisConstants.LOAD_DELAY_MS;
		}
		if (currentTime < nextBigQueryJobTime) {
			memcache.increment(AnalysisConstants.LAST_BIGQUERY_JOB_TIME, -AnalysisConstants.LOAD_DELAY_MS);
			
			String queueName = AnalysisUtility.extractParameterOrThrow(req, AnalysisConstants.QUEUE_NAME_PARAM);
			Queue taskQueue = QueueFactory.getQueue(queueName);
			taskQueue.add(
					Builder.withUrl(
							AnalysisUtility.getRequestBaseName(req) + 
							"/loadCloudStorageToBigquery?" + req.getQueryString())
						   .method(Method.GET)
						   .etaMillis(nextBigQueryJobTime));
			resp.getWriter().println("Rate limiting BigQuery load job - will retry at " + nextBigQueryJobTime);
			return;
		}
		
		String startMsStr = AnalysisUtility.extractParameterOrThrow(req, AnalysisConstants.START_MS_PARAM);
		long startMs = Long.parseLong(startMsStr);
		
		String endMsStr = AnalysisUtility.extractParameterOrThrow(req, AnalysisConstants.END_MS_PARAM);
		long endMs = Long.parseLong(endMsStr);
		
		String bucketName = AnalysisUtility.extractParameterOrThrow(req, AnalysisConstants.BUCKET_NAME_PARAM);
		String bigqueryProjectId = AnalysisUtility.extractParameterOrThrow(req, AnalysisConstants.BIGQUERY_PROJECT_ID_PARAM);	
		String bigqueryDatasetId = AnalysisUtility.extractParameterOrThrow(req, AnalysisConstants.BIGQUERY_DATASET_ID_PARAM);
		String bigqueryTableId = AnalysisUtility.extractParameterOrThrow(req, AnalysisConstants.BIGQUERY_TABLE_ID_PARAM);
		
		String exporterSetClassStr = AnalysisUtility.extractParameterOrThrow(req, AnalysisConstants.BIGQUERY_FIELD_EXPORTER_SET_PARAM);
		BigqueryFieldExporterSet exporterSet = AnalysisUtility.instantiateExporterSet(exporterSetClassStr);		
		String schemaHash = AnalysisUtility.computeSchemaHash(exporterSet);
		
		
		AppIdentityCredential credential = new AppIdentityCredential(AnalysisConstants.SCOPES);
		HttpRequestFactory requestFactory = HTTP_TRANSPORT.createRequestFactory(credential);
		
		List<String> urisToProcess = new ArrayList<String>();
		AnalysisUtility.fetchCloudStorageUris(
				bucketName, schemaHash, startMs, endMs, requestFactory, urisToProcess, false);
		resp.getWriter().println("Got " + urisToProcess.size() + " uris to process");
		if (urisToProcess.isEmpty()) {
			return;
		}
		
		for (String uri : urisToProcess) {
			resp.getWriter().println("URI: " + uri);
		}
		
		Bigquery bigquery = Bigquery.builder(HTTP_TRANSPORT, JSON_FACTORY)
				.setHttpRequestInitializer(credential)
				.setApplicationName("Streak Logs")
				.build();
		
		Job job = new Job();
		JobConfiguration config = new JobConfiguration();
		JobConfigurationLoad loadConfig = new JobConfigurationLoad();
		
		loadConfig.setSourceUris(urisToProcess);
		
		TableSchema schema = new TableSchema();
		// TODO(frew): Support for multiple schemas?
		loadLogsSchema(urisToProcess.get(0), schema);
		loadConfig.setSchema(schema);
		
		TableReference table = new TableReference();
		table.setProjectId(bigqueryProjectId);
		table.setDatasetId(bigqueryDatasetId);
		table.setTableId(bigqueryTableId);
		loadConfig.setDestinationTable(table);
		
		config.setLoad(loadConfig);
		job.setConfiguration(config);
		Insert insert = bigquery.jobs().insert(bigqueryProjectId, job);
		
		// TODO(frew): Not sure this is necessary, but monkey-see'ing the example code
		insert.setProjectId(bigqueryProjectId);
		JobReference ref = insert.execute().getJobReference();
		resp.getWriter().println("Successfully started job " + ref);
	}
	
	private void loadLogsSchema(String fileUri, TableSchema schema) throws IOException  {
		// Hack(frew): Move to AnalysisUtility
		String schemaFileUri = fileUri + ".schema";
		String schemaFileName = "/gs/" + schemaFileUri.substring(schemaFileUri.indexOf("//") + 2);
		BlobstoreService blobstoreService = BlobstoreServiceFactory.getBlobstoreService();
		BlobKey key = blobstoreService.createGsBlobKey(schemaFileName);
		
		FileService fileService = FileServiceFactory.getFileService();
		AppEngineFile schemaFile = new AppEngineFile(schemaFileName);
		FileReadChannel readChannel = fileService.openReadChannel(schemaFile, false);
		BufferedReader reader = new BufferedReader(Channels.newReader(readChannel, "UTF8"));
		String schemaLine;
		try {
			schemaLine = reader.readLine().trim();
		} catch (NullPointerException npe) {
			throw new IOException("Encountered NPE reading " + schemaFileName);
		}
		reader.close();
		readChannel.close();
		
		String[] schemaFieldStrs = schemaLine.split(",");
		List<TableFieldSchema> schemaFields = new ArrayList<TableFieldSchema>(schemaFieldStrs.length);
		for (String schemaFieldStr : schemaFieldStrs) {
			TableFieldSchema field = new TableFieldSchema();
			String[] schemaFieldStrParts = schemaFieldStr.split(":");
			field.setName(schemaFieldStrParts[0]);
			field.setType(schemaFieldStrParts[1]);
			schemaFields.add(field);
		}
		schema.setFields(schemaFields);
	}
}