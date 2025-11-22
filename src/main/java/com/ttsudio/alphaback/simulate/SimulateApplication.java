package com.ttsudio.alphaback.simulate;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ttsudio.alphaback.Model;
import com.ttsudio.alphaback.State;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@SpringBootApplication
@RestController
public class SimulateApplication {
    static final String GATHER_DATA_FUNCTION_NAME = "gatherData";

    Logger logger = LoggerFactory.getLogger(getClass());
    LambdaClient lambdaClient = LambdaClient.create();
    public static void main(String[] args) {
      SpringApplication.run(SimulateApplication.class, args);
    }

    @PostMapping(path ="/simulate", produces = MediaType.APPLICATION_JSON_VALUE)
    public SimulationResponse simulate(
        // @RequestParam(value="stocks") List<String> stocks,
        // @RequestParam(value="modelId") String modelId,
        // @RequestParam(value="startDate") String startDate,
        // @RequestParam(value="endDate") String endDate,
        @RequestParam(value="timeStep", defaultValue="TIME_SERIES_DAILY") String timeStep) {
            File modelDir = new File("models");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{modelDir.toURI().toURL()})) {
              Class<?> clazz = loader.loadClass("com.ttsudio.alphaback.ExampleModel");
              logger.info(clazz.getSimpleName());
              if(!Model.class.isAssignableFrom(clazz)) {
                throw new RuntimeException("invalid class");
              }

              Model model = (Model) clazz.getDeclaredConstructor().newInstance();

              InvokeResponse res = lambdaClient.invoke(
                InvokeRequest.builder()
                  .functionName(GATHER_DATA_FUNCTION_NAME)
                  .payload(SdkBytes.fromUtf8String("{"
                    +"\"function\": \"" + timeStep + "\","
                    +"\"symbol\": \"AAPL\""
                  +"}"))
                  .build()
              );
              String payload = res.payload().asUtf8String();
              logger.info("Lambda response: " + payload);

              ObjectMapper mapper = new ObjectMapper();
              JsonNode root = mapper.readTree(payload);

              // navigate to time series node (supports payload wrapped under "data")
              JsonNode dataNode = root.has("data") ? root.get("data") : root;
              String timeSeriesKey = null;
              // collect field names into a list (Iterator -> List)
              List<String> dataKeys = new ArrayList<>();
              dataNode.fieldNames().forEachRemaining(dataKeys::add);
              for (String key : dataKeys) {
                if (key.toLowerCase().contains("time series")) {
                  timeSeriesKey = key;
                  break;
                }
              }
              JsonNode tsNode = timeSeriesKey != null ? dataNode.get(timeSeriesKey) : dataNode.get("Time Series (Daily)");

              // symbol from meta if available
              String symbol = "UNKNOWN";
              if (dataNode.has("Meta Data") && dataNode.get("Meta Data").has("2. Symbol")) {
                symbol = dataNode.get("Meta Data").get("2. Symbol").asText();
              }

              SimulationResponse simResp = new SimulationResponse();
              double startingCapital = 100000.0;
              double cash = startingCapital;
              Map<String, Float> owned = new HashMap<>();

              if (tsNode != null && tsNode.isObject()) {
                List<String> dates = new ArrayList<>();
                tsNode.fieldNames().forEachRemaining(dates::add);
                // sort from oldest -> newest
                Collections.sort(dates);

                for (String date : dates) {
                  JsonNode dayNode = tsNode.get(date);
                  if (dayNode == null) continue;

                  // try to get close price
                  String closeStr = null;
                  if (dayNode.has("4. close")) closeStr = dayNode.get("4. close").asText();
                  else if (dayNode.has("close")) closeStr = dayNode.get("close").asText();
                  if (closeStr == null) continue;

                  float price = Float.parseFloat(closeStr);

                  Map<String, Float> pricesMap = new HashMap<>();
                  pricesMap.put(symbol, price);

                  // create State (prices, owned)
                  State state = new State(pricesMap, new HashMap<>(owned));

                  // call model (returns list of Orders loaded by child loader)
                  List<?> decisions = model.simulateStep(state);
                  if (decisions != null) {
                    for (Object ord : decisions) {
                      try {
                        Method mStock = ord.getClass().getMethod("stock");
                        Method mAmount = ord.getClass().getMethod("amount");
                        Method mIsBuy = ord.getClass().getMethod("isBuy");

                        String stock = (String) mStock.invoke(ord);
                        Float amount = (Float) mAmount.invoke(ord);
                        Boolean isBuy = (Boolean) mIsBuy.invoke(ord);

                        // apply order
                        if (Boolean.TRUE.equals(isBuy)) {
                          double cost = amount * price;
                          if (cash >= cost) {
                            cash -= cost;
                            owned.put(stock, owned.getOrDefault(stock, 0f) + amount);
                          } else {
                            // not enough cash -> skip
                          }
                        } else {
                          float have = owned.getOrDefault(stock, 0f);
                          float toSell = Math.min(have, amount);
                          cash += toSell * price;
                          if (toSell >= have) owned.remove(stock);
                          else owned.put(stock, have - toSell);
                        }

                        simResp.getDecisions().add(new SimulationResponse.Decision(date, stock, amount, isBuy));
                      } catch (NoSuchMethodException nsme) {
                        logger.warn("Unexpected order shape", nsme);
                      }
                    }
                  }
                }
              }

              // compute ending capital using last known price
              double holdingsValue = 0.0;
              // if we have any owned assets, try last price from tsNode
              float lastPrice = 0f;
              if (tsNode != null) {
                List<String> datesAll = new ArrayList<>();
                tsNode.fieldNames().forEachRemaining(datesAll::add);
                Collections.sort(datesAll);
                if (!datesAll.isEmpty()) {
                  JsonNode lastDay = tsNode.get(datesAll.get(datesAll.size() - 1));
                  if (lastDay != null) {
                    String close = lastDay.has("4. close") ? lastDay.get("4. close").asText() : (lastDay.has("close") ? lastDay.get("close").asText() : null);
                    if (close != null) lastPrice = Float.parseFloat(close);
                  }
                }
              }
              for (Map.Entry<String, Float> e : owned.entrySet()) {
                // assume lastPrice applies for the symbol
                holdingsValue += e.getValue() * lastPrice;
              }

              double endingCapital = cash + holdingsValue;
              double gainPct = (endingCapital - startingCapital) / startingCapital * 100.0;

              simResp.setStatus("OK");
              simResp.setStartingCapital(startingCapital);
              simResp.setEndingCapital(endingCapital);
              simResp.setGainPercentage(gainPct);

              logger.info("Simulation finished: gain%=" + gainPct);
              return simResp;
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          
    }

    @GetMapping("/hello")
    public String hello(@RequestParam(value = "name", defaultValue = "World") String name) {
      return String.format("Hello %s!", name);
    }
}
