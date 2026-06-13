package main.model.domain;

public record OptimizationProposal(
    int rsiOS, int rsiOB,
    double slMult, double tpMult,
    double bbWidthMult,
    double returnPct, double winRate, double mdd
) {}
