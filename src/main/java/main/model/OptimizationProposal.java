package main.model;

public record OptimizationProposal(
    int rsiOS, int rsiOB,
    double slMult, double tpMult,
    double bbWidthMult,
    double returnPct, double winRate, double mdd
) {}
