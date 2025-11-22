package com.ttsudio.alphaback.simulate;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SimulationResponse {
    private String status;
    private Double gainPercentage;
    private Double startingCapital;
    private Double endingCapital;
    private List<Decision> decisions = new ArrayList<>();

    @Data
    @NoArgsConstructor
    public static class Decision {
        private String date;
        private String stock;
        private Float amount;
        private Boolean isBuy;

        public Decision(String date, String stock, Float amount, Boolean isBuy) {
            this.date = date;
            this.stock = stock;
            this.amount = amount;
            this.isBuy = isBuy;
        }
    }
}
