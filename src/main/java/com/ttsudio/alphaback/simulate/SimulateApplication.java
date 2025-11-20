package com.ttsudio.alphaback.simulate;

import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class SimulateApplication {
    public static void main(String[] args) {
      SpringApplication.run(SimulateApplication.class, args);
    }

    @PostMapping(path ="/simulate", produces = "application/json")
    public SimulationResponse simulate(
        @RequestParam(value="stocks") List<String> stocks,
        @RequestParam(value="modelId") String modelId,
        @RequestParam(value="startDate") String startDate,
        @RequestParam(value="endDate") String endDate,
        @RequestParam(value="timeStep", defaultValue="day") String timeStep) {


        return new SimulationResponse();
    }

    @GetMapping("/hello")
    public String hello(@RequestParam(value = "name", defaultValue = "World") String name) {
      return String.format("Hello %s!", name);
    }
}
