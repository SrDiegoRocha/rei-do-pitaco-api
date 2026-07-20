package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.enums.TiebreakCriteria;

import java.util.List;

public record TournamentSettingsResponse(
        int winPoints,
        int drawPoints,
        int lossPoints,
        int exactScorePoints,
        int winnerPoints,
        int wrongPoints,
        int extraTimeExactScorePoints,
        int extraTimeWinnerPoints,
        int penaltyWinnerPoints,
        int pickemQualifierPoints,
        int pickemExactPositionPoints,
        int pickemFirstPlacePoints,
        int pickemKoMatchupExactPoints,
        int pickemKoMatchupPartialPoints,
        int pickemChampionPoints,
        int pickemRunnerUpPoints,
        int pickemThirdPlacePoints,
        List<TiebreakCriteria> tiebreakCriteria
) {
}
