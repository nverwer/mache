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

package com.streak.logging.analysis.example;

import java.util.Arrays;
import java.util.List;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.appengine.api.log.RequestLogs;
import com.streak.logging.analysis.LogsFieldExporter;

public class HttpTransactionFieldExporter implements LogsFieldExporter {
	private static final List<String> NAMES = Arrays.asList(
			"httpStatus", "method", "httpVersion", "requestId");
	
	private int httpStatus;
	private String method;
	private String httpVersion;
	private String requestId;
	
	@Override
	public void processLog(RequestLogs log) {
		httpStatus = log.getStatus();
		method = log.getMethod();
		httpVersion = log.getHttpVersion();
		requestId = log.getRequestId();
	}

	@Override
	public Object getField(String name) {
		if (name == "httpStatus") {
			return httpStatus;
		}
		if (name == "method") {
			return method;
		}
		if (name == "httpVersion") {
			return httpVersion;
		}
		if (name == "requestId") {
			return requestId;
		}
		
		return null;
	}

	@Override
	public int getFieldCount() {
		return NAMES.size();
	}

	@Override
	public String getFieldName(int i) {
		return NAMES.get(i);
	}

	@Override
	public String getFieldType(int i) {
		if (i == 0) {
			return "integer";
		}
		return "string";
	}

	@Override
	public boolean getFieldNullable(int i) {
		return false;
	}
	
	@Override
	public boolean getFieldRepeated(int i) {
		return false;
	}

	@Override
	public List<TableFieldSchema> getFieldFields(int i) {
		return null;
	}

}
