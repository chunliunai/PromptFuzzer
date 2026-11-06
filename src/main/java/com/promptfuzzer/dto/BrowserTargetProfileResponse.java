package com.promptfuzzer.dto;

import com.promptfuzzer.entity.BrowserTargetProfile;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BrowserTargetProfileResponse {

    private Long id;
    private String targetType;
    private String displayName;
    private String chatUrl;
    private String selectorsJson;
    private String loginJson;
    private String loginCheckJson;
    private Integer waitTimeoutMs;
    private String profilePath;
    private String authState;
    private LocalDateTime lastVerifiedAt;
    private LocalDateTime lastLoginAt;
    private String lastFailureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static BrowserTargetProfileResponse from(BrowserTargetProfile profile) {
        BrowserTargetProfileResponse response = new BrowserTargetProfileResponse();
        response.setId(profile.getId());
        response.setTargetType(profile.getTargetType());
        response.setDisplayName(profile.getDisplayName());
        response.setChatUrl(profile.getChatUrl());
        response.setSelectorsJson(profile.getSelectorsJson());
        response.setLoginJson(profile.getLoginJson());
        response.setLoginCheckJson(profile.getLoginCheckJson());
        response.setWaitTimeoutMs(profile.getWaitTimeoutMs());
        response.setProfilePath(profile.getProfilePath());
        response.setAuthState(profile.getAuthState().name());
        response.setLastVerifiedAt(profile.getLastVerifiedAt());
        response.setLastLoginAt(profile.getLastLoginAt());
        response.setLastFailureReason(profile.getLastFailureReason());
        response.setCreatedAt(profile.getCreatedAt());
        response.setUpdatedAt(profile.getUpdatedAt());
        return response;
    }

    public static BrowserTargetProfileResponse missing(String targetType) {
        BrowserTargetProfileResponse response = new BrowserTargetProfileResponse();
        response.setTargetType(targetType == null || targetType.isBlank() ? "default" : targetType);
        response.setDisplayName(response.getTargetType());
        response.setAuthState(BrowserTargetProfile.AuthState.UNKNOWN.name());
        response.setProfilePath("browser-auth/" + response.getTargetType().replaceAll("[^A-Za-z0-9._-]", "_") + "-profile");
        return response;
    }
}
