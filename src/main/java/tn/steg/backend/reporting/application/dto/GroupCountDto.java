package tn.steg.backend.reporting.application.dto;

import tn.steg.backend.reporting.domain.model.GroupCount;

/**
 * Read-only dashboard row: a grouping key plus its aggregate count.
 */
public record GroupCountDto(String groupName, long count) {

    public static GroupCountDto from(GroupCount row) {
        return new GroupCountDto(row.getGroupName(), row.getCount());
    }
}