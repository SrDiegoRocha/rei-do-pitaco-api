package com.example.futbet.service;

import com.example.futbet.dto.response.TournamentMemberResponse;
import com.example.futbet.dto.response.TournamentResponse;
import com.example.futbet.entity.Tournament;
import com.example.futbet.entity.TournamentMember;
import com.example.futbet.entity.User;
import com.example.futbet.enums.TournamentMemberRole;
import com.example.futbet.enums.TournamentMemberStatus;
import com.example.futbet.enums.TournamentStatus;
import com.example.futbet.exception.AlreadyTournamentMemberException;
import com.example.futbet.exception.CannotLeaveAsOwnerException;
import com.example.futbet.exception.NotTournamentOwnerException;
import com.example.futbet.exception.TournamentFullException;
import com.example.futbet.exception.TournamentMemberBannedException;
import com.example.futbet.exception.TournamentNotEditableException;
import com.example.futbet.exception.TournamentNotFoundException;
import com.example.futbet.mapper.TournamentMapper;
import com.example.futbet.mapper.TournamentMemberMapper;
import com.example.futbet.repository.TournamentMemberRepository;
import com.example.futbet.repository.TournamentRepository;
import com.example.futbet.repository.TournamentTeamRepository;
import com.example.futbet.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class TournamentMemberService {

    private final TournamentRepository tournamentRepository;
    private final TournamentMemberRepository memberRepository;
    private final TournamentTeamRepository teamRepository;
    private final UserRepository userRepository;
    private final TournamentMapper tournamentMapper;
    private final TournamentMemberMapper memberMapper;

    public TournamentMemberService(
            TournamentRepository tournamentRepository,
            TournamentMemberRepository memberRepository,
            TournamentTeamRepository teamRepository,
            UserRepository userRepository,
            TournamentMapper tournamentMapper,
            TournamentMemberMapper memberMapper
    ) {
        this.tournamentRepository = tournamentRepository;
        this.memberRepository = memberRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.tournamentMapper = tournamentMapper;
        this.memberMapper = memberMapper;
    }

    @Transactional
    public TournamentResponse joinByCode(UUID userPublicId, String inviteCode) {
        Tournament tournament = tournamentRepository.findByInviteCodeAndActiveTrue(inviteCode)
                .orElseThrow(TournamentNotFoundException::new);

        if (tournament.getStatus() == TournamentStatus.DRAFT
                || tournament.getStatus() == TournamentStatus.FINISHED) {
            throw new TournamentNotEditableException(
                    tournament.getStatus(),
                    "tournament is not accepting members"
            );
        }

        User user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(TournamentNotFoundException::new);

        TournamentMember member = memberRepository
                .findByTournamentPublicIdAndUserPublicId(tournament.getPublicId(), userPublicId)
                .orElse(null);

        if (member != null) {
            switch (member.getStatus()) {
                case ACTIVE -> throw new AlreadyTournamentMemberException();
                case BANNED -> throw new TournamentMemberBannedException();
                case LEFT -> reactivate(tournament, member);
            }
        } else {
            assertCapacity(tournament);
            memberRepository.save(TournamentMember.builder()
                    .tournament(tournament)
                    .user(user)
                    .role(TournamentMemberRole.PARTICIPANT)
                    .status(TournamentMemberStatus.ACTIVE)
                    .build());
        }

        return buildResponse(tournament);
    }

    @Transactional(readOnly = true)
    public Page<TournamentMemberResponse> list(UUID tournamentPublicId, Pageable pageable) {
        return memberRepository.findAllByTournamentPublicId(tournamentPublicId, pageable)
                .map(memberMapper::toResponse);
    }

    @Transactional
    public void leave(UUID userPublicId, UUID tournamentPublicId) {
        TournamentMember member = memberRepository
                .findByTournamentPublicIdAndUserPublicId(tournamentPublicId, userPublicId)
                .orElseThrow(TournamentNotFoundException::new);

        if (member.getRole() == TournamentMemberRole.OWNER) {
            throw new CannotLeaveAsOwnerException();
        }
        if (member.getStatus() != TournamentMemberStatus.ACTIVE) {
            throw new TournamentNotFoundException();
        }

        member.setStatus(TournamentMemberStatus.LEFT);
        member.setLeftAt(Instant.now());
    }

    @Transactional
    public void ban(UUID ownerPublicId, UUID tournamentPublicId, UUID targetUserPublicId) {
        Tournament tournament = tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
        if (!tournament.getOwner().getPublicId().equals(ownerPublicId)) {
            throw new NotTournamentOwnerException();
        }
        if (targetUserPublicId.equals(ownerPublicId)) {
            throw new CannotLeaveAsOwnerException();
        }

        TournamentMember member = memberRepository
                .findByTournamentPublicIdAndUserPublicId(tournamentPublicId, targetUserPublicId)
                .orElseThrow(TournamentNotFoundException::new);

        Instant now = Instant.now();
        member.setStatus(TournamentMemberStatus.BANNED);
        member.setBannedAt(now);
        if (member.getLeftAt() == null) {
            member.setLeftAt(now);
        }
    }

    private void reactivate(Tournament tournament, TournamentMember member) {
        assertCapacity(tournament);
        member.setStatus(TournamentMemberStatus.ACTIVE);
        member.setLeftAt(null);
    }

    private void assertCapacity(Tournament tournament) {
        if (tournament.getMaxParticipants() == null) {
            return;
        }
        long active = memberRepository.countByTournamentIdAndStatus(
                tournament.getId(),
                TournamentMemberStatus.ACTIVE
        );
        if (active >= tournament.getMaxParticipants()) {
            throw new TournamentFullException("participants");
        }
    }

    private TournamentResponse buildResponse(Tournament tournament) {
        long memberCount = memberRepository.countByTournamentIdAndStatus(
                tournament.getId(),
                TournamentMemberStatus.ACTIVE
        );
        long teamCount = teamRepository.countByTournamentId(tournament.getId());
        return tournamentMapper.toResponse(tournament, memberCount, teamCount);
    }
}
