package com.tarento.analytics.handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarento.analytics.helper.ComputedFieldFactory;
import com.tarento.analytics.helper.IComputedField;
import com.tarento.analytics.model.ComputedFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tarento.analytics.constant.Constants;
import com.tarento.analytics.dto.AggregateDto;
import com.tarento.analytics.dto.AggregateRequestDto;
import com.tarento.analytics.dto.Data;
import com.tarento.analytics.dto.Plot;

import static com.tarento.analytics.constant.Constants.JsonPaths.DAYS;

/**
 * This handles ES response for single index, multiple index to represent data as line chart
 * Creates plots by merging/computing(by summation) index values for same key
 * AGGS_PATH : this defines the path/key to be used to search the tree
 * VALUE_TYPE : defines the data type for the value formed, this could be amount, percentage, number
 *
 */
@Component
public class LineChartResponseHandler implements IResponseHandler {
    public static final Logger logger = LoggerFactory.getLogger(LineChartResponseHandler.class);
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private ComputedFieldFactory computedFieldFactory;

    @Override
    public AggregateDto translate(AggregateRequestDto requestDto, ObjectNode aggregations) throws IOException {

        List<Data> dataList = new LinkedList<>();

        //String json = "{\"ptindex-v1\":{\"Closed Application\":{\"buckets\":[{\"key_as_string\":\"2018-11-12T00:00:00.000Z\",\"key\":1541980800000,\"doc_count\":1,\"Applications Closed\":{\"buckets\":{\"closed\":{\"doc_count\":0,\"Count\":{\"value\":0}}}}}]},\"Total Application\":{\"buckets\":[{\"key_as_string\":\"2018-11-12T00:00:00.000Z\",\"key\":1541980800000,\"doc_count\":1,\"Count\":{\"value\":1}}]}},\"tlindex-v1\":{\"Closed Application\":{\"buckets\":[{\"key_as_string\":\"2019-04-29T00:00:00.000Z\",\"key\":1556496000000,\"doc_count\":6,\"Applications Closed\":{\"buckets\":{\"closed\":{\"doc_count\":0,\"Count\":{\"value\":0}},\"resolved\":{\"doc_count\":0,\"Count\":{\"value\":0}}}}}]},\"Total Application\":{\"buckets\":[{\"key\":1555891200000,\"doc_count\":1,\"Count\":{\"value\":1}},{\"key\":1556496000000,\"doc_count\":0,\"Count\":{\"value\":0}}]}},\"pgrindex-v1\":{\"Closed Application\":{\"buckets\":[{\"key\":1564963200000,\"doc_count\":438,\"Applications Closed\":{\"buckets\":{\"closed\":{\"doc_count\":5,\"Count\":{\"value\":5}}}}}]},\"Total Application\":{\"buckets\":[{\"key\":1564963200000,\"doc_count\":438,\"Count\":{\"value\":438}},{\"key\":1574035200000,\"doc_count\":3,\"Count\":{\"value\":3}}]}}}";
        JsonNode aggregationNode = aggregations.get(AGGREGATIONS);
        JsonNode chartNode = requestDto.getChartNode();
        boolean isRequestInterval = null == requestDto.getRequestDate() ? false : requestDto.getRequestDate().getInterval()!=null && !requestDto.getRequestDate().getInterval().isEmpty();
        String interval = isRequestInterval ? requestDto.getRequestDate().getInterval(): chartNode.get(Constants.JsonPaths.INTERVAL).asText();
        if(interval == null || interval.isEmpty()){
            throw new RuntimeException("Interval must have value from config or request");
        }

        String symbol = chartNode.get(IResponseHandler.VALUE_TYPE).asText();
        ArrayNode aggrsPaths = (ArrayNode) chartNode.get(IResponseHandler.AGGS_PATH);
        Set<String> plotKeys = new LinkedHashSet<>();
        boolean isCumulative = chartNode.get("isCumulative").asBoolean();

        JsonNode computedFields = chartNode.get(COMPUTED_FIELDS);
        boolean executeComputedFields = computedFields !=null && computedFields.isArray();


        aggrsPaths.forEach(headerPath -> {
            List<JsonNode> aggrNodes = aggregationNode.findValues(headerPath.asText());

            Map<String, Double> plotMap = new LinkedHashMap<>();
            List<Double> totalValues = new ArrayList<>();
            aggrNodes.stream().forEach(aggrNode -> {
                if (aggrNode.findValues(IResponseHandler.BUCKETS).size() > 0) {

                    ArrayNode buckets = (ArrayNode) aggrNode.findValues(IResponseHandler.BUCKETS).get(0);
                    buckets.forEach(bucket -> {
                        String bkey = bucket.findValue(IResponseHandler.KEY).asText();
                        String key = getIntervalKey(bkey, Constants.Interval.valueOf(interval));
                        

                        plotKeys.add(key);
                        double previousVal = 0.0;//!isCumulative ? 0.0 : (totalValues.size()>0 ? totalValues.get(totalValues.size()-1):0.0);  //Commented by Srikanth V. All cummulative additions are handled seperately in the end.

                        double value = 0.0;
                        if(executeComputedFields){
                            try {

                                List<ComputedFields> computedFieldsList = mapper.readValue(computedFields.toString(), new TypeReference<List<ComputedFields>>(){});

                                for(ComputedFields cfs :computedFieldsList){
                                    IComputedField computedFieldObject = computedFieldFactory.getInstance(cfs.getActionName());
                                    computedFieldObject.set(requestDto, cfs.getPostAggregationTheory());
                                    computedFieldObject.add(bucket, cfs.getFields(), cfs.getNewField());

                                    if(symbol.equals(DAYS)){

                                        long milidiff = bucket.findValue(cfs.getNewField()).get(IResponseHandler.VALUE).asLong();
                                        long days = TimeUnit.MILLISECONDS.toDays(milidiff);
                                        value = previousVal + (days);

                                    } else {
                                        value = previousVal + (bucket.findValue(cfs.getNewField()).get(IResponseHandler.VALUE).asLong());
                                    }
                                }

                            } catch (Exception e){
                                logger.error("execution of computed field :"+e.getMessage());
                            }

                        } else {
                            value = previousVal + ((bucket.findValue(IResponseHandler.VALUE) != null) ? bucket.findValue(IResponseHandler.VALUE).asDouble():bucket.findValue(IResponseHandler.DOC_COUNT).asDouble());
                        }
                        //double value = previousVal + ((bucket.findValue(IResponseHandler.VALUE) != null) ? bucket.findValue(IResponseHandler.VALUE).asDouble():bucket.findValue(IResponseHandler.DOC_COUNT).asDouble());
                       // System.out.println("New Value: "+value + "Check sum: "+plotMap.get(key) +" + "+ value);
                        //Double plotMapValueInserted = plotMap.get(key) == null ? new Double("0") + value : plotMap.get(key) + value;
                        //System.out.println("    Printing keys "+key+" value:"+ value +" PlotMap Value inserted "+plotMapValueInserted);;
                        plotMap.put(key, plotMap.get(key) == null ? new Double("0") + value : plotMap.get(key) + value);
                        //plotMap.put(key, plotMap.get(key) == null ? new Double("0") + value :  value);
                        totalValues.add(value);
                        //System.out.println("Total values: "+totalValues);
                    });
                }
            });
            
			
			  //System.out.println("Check plot map:"); for (String key : plotMap.keySet()) {
			  //System.out.println(key +" - "+ plotMap.get(key)); }

			 
            List<Plot> plots = plotMap.entrySet().stream().map(e -> new Plot(e.getKey(), e.getValue(), symbol)).collect(Collectors.toList());
			
			  //System.out.println("Plots is: "+plots); for (Plot plt : plots) {
			  //System.out.println(plt.getName()+" - "+plt.getValue()); }
			 
            try{
                Data data = new Data(headerPath.asText(), (totalValues==null || totalValues.isEmpty()) ? 0.0 : totalValues.stream().reduce(0.0, Double::sum), symbol);
                data.setPlots(plots);
                dataList.add(data);
            } catch (Exception e) {
                logger.error(" Legend/Header "+headerPath.asText() +" exception occurred "+e.getMessage());
            }
        });

       
        dataList.forEach(data -> {
            appendMissingPlot(plotKeys, data, symbol, isCumulative);
        });
		
        //Added by Srikanth V
        dataList.forEach(data -> {
        	List<Plot> plotsList = sortPlotList(data.getPlots());//Final sorting
        	if(isCumulative)//Final cumulative addition if required.
        	{
	        	Double[] prevValue = {0.0};
	        	plotsList.forEach(p -> {
	        		p.setValue(p.getValue() + prevValue[0]);
	        		prevValue[0]=p.getValue();
	        	});
        	}
        });
        return getAggregatedDto(chartNode, dataList, requestDto.getVisualizationCode());
    }

    private String getIntervalKey(String epocString, Constants.Interval interval) {
        try {
            long epoch = Long.parseLong( epocString );
            Date expiry = new Date( epoch );
            Calendar cal = Calendar.getInstance();
            cal.setTime(expiry);

            String day = String.valueOf(cal.get(Calendar.DATE));
            String month = monthNames(cal.get(Calendar.MONTH)+1);
            String year =  ""+cal.get(Calendar.YEAR);

            String intervalKey = "";
            if(interval.equals(Constants.Interval.day)) {
                intervalKey = day.concat("-").concat(month).concat("-").concat(year);
            } else if(interval.equals(Constants.Interval.week)){
                intervalKey = day.concat("-").concat(month).concat("-").concat(year);
            } else if(interval.equals(Constants.Interval.year)){
                intervalKey = year;
            } else if(interval.equals(Constants.Interval.month)){
                intervalKey = month.concat("-").concat(year);
            } else {
                throw new RuntimeException("Invalid interval");
            }

            //String weekMonth = "Week " + cal.get(Calendar.WEEK_OF_YEAR)  /*+ " : " +  dayMonth*/;//+" of Month "+ (cal.get(Calendar.MONTH) + 1);
            return intervalKey;
        } catch (Exception e) {
            return epocString;
        }
    }

    private String monthNames(int month) {
        if(month == 1)
            return "Jan";
        else if(month == 2)
            return "Feb";
        else if(month == 3)
            return "Mar";
        else if(month == 4)
            return "Apr";
        else if(month == 5)
            return "May";
        else if(month == 6)
            return "Jun";
        else if(month == 7)
            return "Jul";
        else if(month == 8)
            return "Aug";
        else if(month == 9)
            return "Sep";
        else if(month == 10)
            return "Oct";
        else if(month == 11)
            return "Nov";
        else if(month == 12)
            return "Dec";
        else
            return "Month";
    }
	
	/**
	 * Given the month, the function returns the month value.
	 * 
	 * @param month
	 * @return month Value.
	 * @author Srikanth V
	 */
	private int getMonthValue(String month)
	{
		switch(month)
		{
			case "Jan": return 1;
			case "Feb": return 2;
			case "Mar": return 3;
			case "Apr": return 4;
			case "May": return 5;
			case "Jun": return 6;
			case "Jul": return 7;
			case "Aug": return 8;
			case "Sep": return 9;
			case "Oct": return 10;
			case "Nov": return 11;
			case "Dec": return 12;
			default:  return 0;
		}
			
	}
	
	/**
	 * Sorts the plots based on the date value (name) present inside the Plot.
	 * Ex: [3-Aug-2020, 4-Aug-2020, 5-Aug-2020, 6-Aug-2020, 2-Aug-2020] 
	 * will be sorted as [2-Aug-2020, 3-Aug-2020, 4-Aug-2020, 5-Aug-2020, 6-Aug-2020]
	 * @param plots
	 * @return sorted Plots.
	 * @author Srikanth V
	 */
	private List<Plot> sortPlotList(List<Plot> plots) {
		
		plots.sort(new Comparator<Plot>() {
		    @Override
		    public int compare(Plot plot1, Plot plot2) {
		    	if(Integer.parseInt(plot1.getName().split("-")[2]) > Integer.parseInt(plot2.getName().split("-")[2])){
		            return 1;
		        }
		        else
		        if(Integer.parseInt(plot1.getName().split("-")[2]) < Integer.parseInt(plot2.getName().split("-")[2]))
		        {
		        	return -1;
		        }
		        else
		        if(getMonthValue(plot1.getName().split("-")[1]) > getMonthValue(plot2.getName().split("-")[1])){
		            return 1;
		        }
		        else
		        if(getMonthValue(plot1.getName().split("-")[1]) < getMonthValue(plot2.getName().split("-")[1]))
		        {
		        	return -1;
		        }
		        else
		        {
		        	if(Integer.parseInt(plot1.getName().split("-")[0]) > Integer.parseInt(plot2.getName().split("-")[0]))
		        		return 1;
		        	else
		        	if(Integer.parseInt(plot1.getName().split("-")[0]) < Integer.parseInt(plot2.getName().split("-")[0]))
		        		return -1;
		        	else
		        		return 0;
		        }
		        
		     }
		});
		return plots;
	}
}
