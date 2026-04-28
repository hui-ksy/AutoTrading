package main.model;

public record OptimizationProposal(
    int rsiOS, int rsiOB,
    double slMult, double tpMult,
    double returnPct, double winRate, double mdd
) {}