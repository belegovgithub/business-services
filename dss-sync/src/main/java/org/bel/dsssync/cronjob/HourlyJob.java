package org.bel.dsssync.cronjob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bel.dsssync.model.SearcherRequest;
import org.bel.dsssync.service.DssSyncService;
import org.egov.common.contract.request.RequestInfo;
import org.egov.tracer.model.CustomException;
import org.egov.tracer.model.ServiceCallException;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.gson.JsonObject;
import com.jayway.jsonpath.JsonPath;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class HourlyJob implements Job {
	
	@Autowired
	RestTemplate restTemplate;

	@Autowired
	DssSyncService dssservice;
	
	@Value("${egov.searcher.host}")
	public String searcherHost;

	@Value("${egov.searcher.endpoint}")
	public String searcherEndpoint;
	
	private int totalCitizensRegistered=0;
	
	@Override
	public void execute(JobExecutionContext jobExecutionContext) {
		log.info(" esignMaxTimeMilli ");
		List<Map<String, Object>> dataCitizens = getRainmakerData();
		for (Map<String, Object> record : dataCitizens) {
				if (record.get("day").equals("Week0")) {
					totalCitizensRegistered = (int)Math.round( (Double)record.get("count"));
				}
		}
		log.info("totalCitizensRegistered : "+totalCitizensRegistered);
		JsonObject  jsonObject = new JsonObject();
		jsonObject.addProperty("citizenCount", totalCitizensRegistered);
		dssservice.putToElasticSearch("dss-citizen-count", "_doc", "1", jsonObject);
	}
	
	public List<Map<String, Object>> getRainmakerData() {
		StringBuilder uri = new StringBuilder();
		ObjectMapper mapper = new ObjectMapper();
		List<Map<String, Object>> data = new ArrayList<>();
		SearcherRequest request = preparePlainSearchReq(uri);
		Optional<Object> response = fetchResult(uri, request);
		try {
			if(response.isPresent()) {
				Object parsedResponse = mapper.convertValue(response.get(), Map.class);
				List<Object> dataParsedToList = mapper.convertValue(JsonPath.read(parsedResponse, "$.data"), List.class);
				for (Object record : dataParsedToList) {
					data.add(mapper.convertValue(record, Map.class));
				}
			}

		} catch (Exception e) {
			throw new CustomException("EMAILER_DATA_RETREIVAL_FAILED", "Failed to retrieve data from the db");
		}
		return data;

	}
	
	public SearcherRequest preparePlainSearchReq(StringBuilder uri) {
		uri.append(searcherHost+searcherEndpoint);
		HashMap<String, Object> param = new HashMap<>();
		param.put("intervalinsecs",604800000 );
		SearcherRequest searcherRequest = SearcherRequest.builder().requestInfo(new RequestInfo()).searchCriteria(param)
				.build();
		return searcherRequest;
	}
	
	public Optional<Object> fetchResult(StringBuilder uri, Object request) {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
		Object response = null;
		try {
			response = restTemplate.postForObject(uri.toString(), request, JsonNode.class);
		} catch (HttpClientErrorException e) {
			log.error("External Service threw an Exception: ", e);
			throw new ServiceCallException(e.getResponseBodyAsString());
		} catch (Exception e) {
			log.error("Exception while fetching from external service: ", e);
		}

		return Optional.ofNullable(response);

	}
}