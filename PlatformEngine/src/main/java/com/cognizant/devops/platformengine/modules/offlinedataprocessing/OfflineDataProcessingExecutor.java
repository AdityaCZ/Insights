/*******************************************************************************
 * Copyright 2017 Cognizant Technology Solutions
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package com.cognizant.devops.platformengine.modules.offlinedataprocessing;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.log4j.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import com.cognizant.devops.platformcommons.config.ApplicationConfigCache;
import com.cognizant.devops.platformcommons.constants.ConfigOptions;
import com.cognizant.devops.platformcommons.core.util.InsightsUtils;
import com.cognizant.devops.platformcommons.dal.neo4j.GraphDBException;
import com.cognizant.devops.platformcommons.dal.neo4j.GraphResponse;
import com.cognizant.devops.platformcommons.dal.neo4j.Neo4jDBHandler;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
/**
 * @author 368419
 *
 */
public class OfflineDataProcessingExecutor implements Job{
	private static Logger log = Logger.getLogger(OfflineDataProcessingExecutor.class);
	private static final String DATE_TIME_FORMAT = "yyyy/MM/dd hh:mm a";

	
	@Override
	public void execute(JobExecutionContext context) throws JobExecutionException {
		processOfflineData();
	}

	private void processOfflineData()  {
		File queryFolderPath = new File(ConfigOptions.OFFLINE_DATA_PROCESSING_RESOLVED_PATH);
		File [] files = queryFolderPath.listFiles();
	    for (int i = 0; i < files.length; i++){
	        if (files[i].isFile()){ //this line removes other directories/folders
	            processEachOfflineVector(files[i]);
	        }
	    }
	}
	
	private void processEachOfflineVector(File jsonFile) {
		
		try (BufferedReader reader = new BufferedReader(new FileReader(jsonFile))) {
			 JsonArray jsonArray = new Gson().fromJson(reader, JsonArray.class);
			 JsonArray updatedJsonArray = new JsonArray();
			 JsonObject updatedJsonObject =null;
			 
			 for (JsonElement jsonElement: jsonArray) {
				 JsonObject jsonObject = jsonElement.getAsJsonObject();
				 if (jsonObject != null) {
					 String queryName = jsonObject.get(ConfigOptions.QUERY_NAME).getAsString();
					 String cypherQuery = jsonObject.get(ConfigOptions.CYPHER_QUERY).getAsString();
					 Long runSchedule = jsonObject.get(ConfigOptions.RUN_SCHEDULE).getAsLong();
					 String lastRunTime = null;
					 if (jsonObject.get(ConfigOptions.LAST_EXECUTION_TIME) != null) {
						 lastRunTime = jsonObject.get(ConfigOptions.LAST_EXECUTION_TIME).getAsString();					 
					 }
					 if (isQueryScheduledToRun(runSchedule, lastRunTime)) {
						 executeCypherQuery(cypherQuery, jsonObject);
						 updatedJsonObject = updateLastExecutionTime(jsonObject);					 
					 } else {
						 updatedJsonObject = jsonObject;
					 }
					 updatedJsonArray.add(updatedJsonObject);				 
				 }
			 }
			 
			//Write into the file
	         try (FileWriter file = new FileWriter(jsonFile)) {
	        	 file.write(updatedJsonArray.toString());
	         } catch (IOException e) {
	        	 log.error("Unable to update data-enrichment.json file.", e);
			}
		} catch (FileNotFoundException e) {
			log.error("data-enrichment.json file not found.", e);
		} catch (IOException e) {
			log.error("Unable to read data-enrichment.json file.", e);
		} 
		
	}
	
	
	/**
	 * Updates lastRunTime in the offline vector file after processing cypher query
	 * 
	 */
	private JsonObject updateLastExecutionTime(JsonObject jsonObject) {
		String lastRunTime = InsightsUtils.getLocalDateTime(DATE_TIME_FORMAT);
		if (jsonObject != null) {
			if (jsonObject.get(ConfigOptions.LAST_EXECUTION_TIME)!= null) {
				jsonObject.remove(ConfigOptions.LAST_EXECUTION_TIME);
			}
			jsonObject.addProperty(ConfigOptions.LAST_EXECUTION_TIME, lastRunTime);
		}
		return jsonObject;		
	}

	/**
	 * Executes cypherQuery and 
	 * adds/updates two attributes "recordsProcessed" and "processingTime"
	 * 
	 * @param cypherQuery
	 * @param jsonObject
	 */
	private void executeCypherQuery(String cypherQuery, JsonObject jsonObject) {
		Neo4jDBHandler dbHandler = new Neo4jDBHandler();
		int processedRecords = 1;
		int recordCount=0;
		long queryExecutionStartTime = System.currentTimeMillis();
		try {
			while (processedRecords > 0) {
				GraphResponse sprintResponse = dbHandler.executeCypherQuery(cypherQuery);
				JsonObject sprintResponseJson = sprintResponse.getJson();
				try {
				processedRecords = sprintResponseJson.getAsJsonArray("results").get(0).getAsJsonObject()
																	.getAsJsonArray("data").get(0).getAsJsonObject()
																	.getAsJsonArray("row").get(0).getAsInt();
				}
				catch (UnsupportedOperationException | IllegalStateException ex) {
					log.error(" <"+cypherQuery + " > query processing failed", ex);
					break;
				}
				log.debug(" Processed "+processedRecords);
				recordCount = recordCount + processedRecords;
			}
			long queryExecutionEndTime = System.currentTimeMillis();
			long queryProcessingTime = (queryExecutionEndTime - queryExecutionStartTime);
			if (jsonObject != null) {
				// Adds or Updates "recordsProcessed" attribute in the data-enrichment.json file
				if (jsonObject.get(ConfigOptions.RECORDS_PROCESSED) != null) {
					jsonObject.remove(ConfigOptions.RECORDS_PROCESSED);
				}
				jsonObject.addProperty(ConfigOptions.RECORDS_PROCESSED, recordCount);
				// Adds or Updates "processingTime" attribute in the data-enrichment.json file
				if (jsonObject.get(ConfigOptions.PROCESSING_TIME)!= null) {
					jsonObject.remove(ConfigOptions.PROCESSING_TIME);
				}
				jsonObject.addProperty(ConfigOptions.PROCESSING_TIME, queryProcessingTime);
			}
			
		}	
		catch (GraphDBException e) {
			log.error(cypherQuery + " query processing failed", e);
		} 
	}

	private Boolean isQueryScheduledToRun(Long runSchedule, String lastRunTime) {
		// if lastExecutionTime property is not added in the json file, we'll execute the query by default
		if (lastRunTime == null) {
			return Boolean.TRUE;
		}
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT).withZone(InsightsUtils.zoneId);
		ZonedDateTime dateTime = null;
		ZonedDateTime now = ZonedDateTime.now(InsightsUtils.zoneId);
		Long timeDifferenceInMinutes = null;
		if (lastRunTime != null && !lastRunTime.isEmpty()) {
			dateTime = ZonedDateTime.parse(lastRunTime, formatter);
		}
		if (dateTime != null && now != null) {
			Duration d = Duration.between(dateTime, now);
			timeDifferenceInMinutes = d.abs().toMinutes();
		}
		if (timeDifferenceInMinutes > runSchedule) {
			return Boolean.TRUE;
		}
		return Boolean.FALSE;
	}	

	
	public static void main(String args[]) {
		ApplicationConfigCache.loadConfigCache();
		OfflineDataProcessingExecutor executor = new OfflineDataProcessingExecutor();
		executor.processOfflineData();
	}
}
