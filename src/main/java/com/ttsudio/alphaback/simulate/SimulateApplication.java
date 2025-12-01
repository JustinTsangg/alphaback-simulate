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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@SpringBootApplication
@RestController
public class SimulateApplication {
    static final String GATHER_DATA_FUNCTION_NAME = "gatherData";
    static final String GET_MODEL_FUNCTION_NAME = "modelRegistryService";
    static final String SERVICE_CONSUMER_FUNCTION_NAME = "serviceConsumer";

    Logger logger = LoggerFactory.getLogger(getClass());
    LambdaClient lambdaClient = LambdaClient.create();
    private final ObjectMapper mapper = new ObjectMapper();

    private static class TimeSeriesData {
        public final Map<String, JsonNode> tsMap;

        public TimeSeriesData(Map<String, JsonNode> tsMap) {
            this.tsMap = tsMap;
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(SimulateApplication.class, args);
    }

    private JsonNode fetchModelBody(String modelId) {
        try {
            InvokeResponse getModelResponse = lambdaClient.invoke(
                    InvokeRequest.builder()
                            .functionName(GET_MODEL_FUNCTION_NAME)
                            .payload(SdkBytes.fromUtf8String(
                                    String.format("""
                                            {
                                              "rawPath": "/models/%s",
                                              "queryStringParameters": {
                                                "parameter1": "value1,value2",
                                                "parameter2": "value"
                                              },
                                              "requestContext": {
                                                "http": {
                                                  "method": "GET",
                                                  "path": "/models/%s",
                                                  "protocol": "HTTP/1.1",
                                                  "sourceIp": "192.168.0.1/32",
                                                  "userAgent": "agent"
                                                }
                                              },
                                              "body": "eyJ0ZXN0IjoiYm9keSJ9",
                                              "pathParameters": {
                                                "parameter1": "value1"
                                              }
                                            }
                                            """, modelId, modelId)))
                            .build());
            String getModelPayload = getModelResponse.payload().asUtf8String();
            logger.info("Get model response: " + getModelPayload);

            JsonNode getModelRoot = mapper.readTree(getModelPayload);
            String bodyStr = getModelRoot.has("body") ? getModelRoot.get("body").asText() : getModelRoot.toString();
            JsonNode bodyJson = mapper.readTree(bodyStr);
            return bodyJson;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private TimeSeriesData fetchTimeSeries(String timeStep, List<String> requestedStocks) {
        try {
            // discover active gather-data function via service consumer
            String serviceArn = null;
            try {
                InvokeResponse svcResp = lambdaClient.invoke(
                        InvokeRequest.builder().functionName(SERVICE_CONSUMER_FUNCTION_NAME)
                                .payload(SdkBytes.fromUtf8String("{}"))
                                .build());
                String svcPayload = svcResp.payload().asUtf8String();
                JsonNode svcRoot = mapper.readTree(svcPayload);
                JsonNode serviceNode = svcRoot.has("service") ? svcRoot.get("service") : svcRoot;
                if (serviceNode != null) {
                    if (serviceNode.has("service_arn")) serviceArn = serviceNode.get("service_arn").asText();
                    else if (serviceNode.has("serviceArn")) serviceArn = serviceNode.get("serviceArn").asText();
                }
            } catch (Exception e) {
                logger.warn("Failed to lookup gather-data service via serviceConsumer; falling back to default", e);
            }

            // normalize ARN to a callable function name: if ARN contains ":function:", use the trailing part
            String gatherFunctionToCall = GATHER_DATA_FUNCTION_NAME;
            if (serviceArn != null && !serviceArn.isEmpty()) {
                int idx = serviceArn.lastIndexOf(":function:");
                if (idx >= 0 && idx + 10 < serviceArn.length()) {
                    gatherFunctionToCall = serviceArn.substring(idx + 10);
                } else {
                    // if it's a bare name or other ARN form, just use it directly
                    gatherFunctionToCall = serviceArn;
                }
            }
            // build payload using requestedStocks if provided (comma-separated string)
            com.fasterxml.jackson.databind.node.ObjectNode payloadNode = mapper.createObjectNode();
            payloadNode.put("function", timeStep);
            if (requestedStocks != null && !requestedStocks.isEmpty()) {
                String csv = requestedStocks.stream().map(String::trim).collect(java.util.stream.Collectors.joining(","));
                // keep field name `symbol` for backward compatibility; value is comma-separated list
                payloadNode.put("symbol", csv);
            }
            String payloadStr = mapper.writeValueAsString(payloadNode);

                InvokeResponse res = lambdaClient.invoke(
                    InvokeRequest.builder()
                        .functionName(gatherFunctionToCall)
                        .payload(SdkBytes.fromUtf8String(payloadStr))
                        .build());
            String payload = res.payload().asUtf8String();
            logger.info("Lambda response: " + payload);

            JsonNode root = mapper.readTree(payload);
            JsonNode dataNode = root.has("data") ? root.get("data") : root;

            Map<String, JsonNode> tsMap = new HashMap<>();

            // If requestedStocks provided, try to load those; otherwise use all keys in dataNode
            List<String> keys = new ArrayList<>();
            if (requestedStocks != null && !requestedStocks.isEmpty()) {
                for (String s : requestedStocks) keys.add(s.trim());
            } else {
                dataNode.fieldNames().forEachRemaining(keys::add);
            }

            for (String key : keys) {
                if (!dataNode.has(key)) continue;
                JsonNode stockNode = dataNode.get(key);
                // find the time series field inside this stock node
                List<String> stockFields = new ArrayList<>();
                stockNode.fieldNames().forEachRemaining(stockFields::add);
                String tsKey = null;
                for (String f : stockFields) {
                    if (f.toLowerCase().contains("time series")) { tsKey = f; break; }
                }
                JsonNode ts = tsKey != null ? stockNode.get(tsKey) : stockNode.get("Time Series (Daily)");
                if (ts != null && ts.isObject()) tsMap.put(key, ts);
            }

            return new TimeSeriesData(tsMap);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping(path = "/simulate", produces = MediaType.APPLICATION_JSON_VALUE)
    public SimulationResponse simulate(
            @RequestParam(value="stocks", defaultValue = "GOOGL, AAPL, NVDA") List<String> stocks,
            @RequestParam(value = "modelId", defaultValue = "726034f9-44c7-49df-9fac-1241da8ef221") String modelId,
            @RequestParam(value = "timeStep", defaultValue = "TIME_SERIES_DAILY") String timeStep) {
                JsonNode bodyJson = fetchModelBody(modelId);
                String downloadUrl = bodyJson.has("downloadUrl") ? bodyJson.get("downloadUrl").asText() : null;
                String classPathRaw = bodyJson.has("classPath") ? bodyJson.get("classPath").asText() : "com/ttsudio/alphaback/ExampleModel";
                logger.info("Model downloadUrl: " + downloadUrl + " classPath: " + classPathRaw);

        File modelDir = new File("models");
            // download class file if a presigned url is present
            if (downloadUrl != null && !downloadUrl.isEmpty()) {
                // normalize class path to use slashes and append .class
                String classPathSlashes = classPathRaw.replace('.', '/');
                classPathSlashes = classPathSlashes.replace('\\', '/');
                if (!classPathSlashes.endsWith(".class")) classPathSlashes = classPathSlashes + ".class";

                File targetFile = new File(modelDir, classPathSlashes);
                try {
                    Files.createDirectories(targetFile.getParentFile().toPath());
                    try (InputStream in = new java.net.URL(downloadUrl).openStream()) {
                        Files.copy(in, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    logger.info("Downloaded model class to: " + targetFile.getAbsolutePath());
                } catch (Exception e) {
                    throw new RuntimeException("Failed to download model class", e);
                }
            }

            try (URLClassLoader loader = new URLClassLoader(new URL[] { modelDir.toURI().toURL() })) {
                String className = classPathRaw.replace('/', '.').replace('\\', '.');
                Class<?> clazz = loader.loadClass(className);
            logger.info(clazz.getSimpleName());
            if (!Model.class.isAssignableFrom(clazz)) {
                throw new RuntimeException("invalid class");
            }

            Model model = (Model) clazz.getDeclaredConstructor().newInstance();

            TimeSeriesData tsData = fetchTimeSeries(timeStep, stocks);
            Map<String, JsonNode> tsMap = tsData.tsMap;

            SimulationResponse simResp = new SimulationResponse();
            double startingCapital = 100000.0;
            double cash = startingCapital;
            Map<String, Float> owned = new HashMap<>();

            // collect union of all dates from all time series
            Set<String> dateSet = new HashSet<>();
            for (JsonNode stockTs : tsMap.values()) {
                stockTs.fieldNames().forEachRemaining(dateSet::add);
            }
            List<String> dates = new ArrayList<>(dateSet);
            Collections.sort(dates);

            // prepare last price map for final valuation
            Map<String, Float> lastPriceMap = new HashMap<>();
            for (Map.Entry<String, JsonNode> e : tsMap.entrySet()) {
                List<String> sd = new ArrayList<>();
                e.getValue().fieldNames().forEachRemaining(sd::add);
                Collections.sort(sd);
                if (!sd.isEmpty()) {
                    JsonNode lastDay = e.getValue().get(sd.get(sd.size() - 1));
                    if (lastDay != null) {
                        String close = lastDay.has("4. close") ? lastDay.get("4. close").asText() : (lastDay.has("close") ? lastDay.get("close").asText() : null);
                        if (close != null) lastPriceMap.put(e.getKey(), Float.parseFloat(close));
                    }
                }
            }

            for (String date : dates) {
                // build prices map for this date
                Map<String, Float> pricesMap = new HashMap<>();
                for (Map.Entry<String, JsonNode> e : tsMap.entrySet()) {
                    String stock = e.getKey();
                    JsonNode stockTs = e.getValue();
                    if (stockTs.has(date)) {
                        JsonNode dayNode = stockTs.get(date);
                        String closeStr = null;
                        if (dayNode.has("4. close")) closeStr = dayNode.get("4. close").asText();
                        else if (dayNode.has("close")) closeStr = dayNode.get("close").asText();
                        if (closeStr != null) {
                            try { pricesMap.put(stock, Float.parseFloat(closeStr)); } catch (NumberFormatException ignore) {}
                        }
                    }
                }

                if (pricesMap.isEmpty()) continue;

                // create State (prices, owned)
                State state = new State(pricesMap, new HashMap<>(owned));

                // call model
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

                            float price = pricesMap.getOrDefault(stock, lastPriceMap.getOrDefault(stock, 0f));

                            // apply order
                            if (Boolean.TRUE.equals(isBuy)) {
                                double cost = amount * price;
                                if (cash >= cost) {
                                    cash -= cost;
                                    owned.put(stock, owned.getOrDefault(stock, 0f) + amount);
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
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                }
            }

            // compute ending capital using last known prices per stock
            double holdingsValue = 0.0;
            for (Map.Entry<String, Float> e : owned.entrySet()) {
                float lp = lastPriceMap.getOrDefault(e.getKey(), 0f);
                holdingsValue += e.getValue() * lp;
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
