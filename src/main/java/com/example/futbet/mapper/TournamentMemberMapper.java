package com.example.futbet.mapper;

import com.example.futbet.dto.response.TournamentMemberResponse;
import com.example.futbet.entity.TournamentMember;
import org.springframework.stereotype.Component;

@Component
public class TournamentMemberMapper {

    public TournamentMemberResponse toResponse(TournamentMember member) {
        return new TournamentMemberResponse(
                member.getUser().getPublicId(),
                member.getUser().getName(),
                member.getUser().getAvatarUrl(),
                member.getRole(),
                member.getStatus(),
                member.getJoinedAt(),
                member.getLeftAt(),
                member.getBannedAt()
        );
    }
}
