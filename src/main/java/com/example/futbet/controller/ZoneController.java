package com.example.futbet.controller;

import com.example.futbet.dto.request.CreateZoneRequest;
import com.example.futbet.dto.request.UpdateZoneRequest;
import com.example.futbet.dto.response.ZoneResponse;
import com.example.futbet.service.ZoneService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments/{tournamentId}/phases/{phaseId}/zones")
public class ZoneController {

    private final ZoneService zoneService;

    public ZoneController(ZoneService zoneService) {
        this.zoneService = zoneService;
    }

    @PostMapping
    public ResponseEntity<ZoneResponse> create(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @Valid @RequestBody CreateZoneRequest request
    ) {
        ZoneResponse response = zoneService.create(
                UUID.fromString(ownerPublicId), tournamentId, phaseId, request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ZoneResponse>> list(
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId
    ) {
        return ResponseEntity.ok(zoneService.list(phaseId));
    }

    @PutMapping("/{zoneId}")
    public ResponseEntity<ZoneResponse> update(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @PathVariable UUID zoneId,
            @Valid @RequestBody UpdateZoneRequest request
    ) {
        return ResponseEntity.ok(
                zoneService.update(UUID.fromString(ownerPublicId), tournamentId, phaseId, zoneId, request)
        );
    }

    @DeleteMapping("/{zoneId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @PathVariable UUID zoneId
    ) {
        zoneService.delete(UUID.fromString(ownerPublicId), tournamentId, phaseId, zoneId);
        return ResponseEntity.noContent().build();
    }
}
