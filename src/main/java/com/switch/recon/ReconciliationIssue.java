package com.qswitch.recon;

public class ReconciliationIssue {
    private final String stan;
    private final String rrn;
    private final String type;
    private final String description;

    public ReconciliationIssue(String stan, String rrn, String type, String description) {
        this.stan = stan;
        this.rrn = rrn;
        this.type = type;
        this.description = description;
    }

    public String getStan() {
        return stan;
    }

    public String getRrn() {
        return rrn;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return "Issue{" +
            "stan='" + stan + '\'' +
            ", rrn='" + rrn + '\'' +
            ", type='" + type + '\'' +
            ", description='" + description + '\'' +
            '}';
    }
}
