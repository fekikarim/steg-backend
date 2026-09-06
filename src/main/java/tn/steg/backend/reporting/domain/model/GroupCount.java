package tn.steg.backend.reporting.domain.model;

/**
 * Read-model projection for a grouped count (e.g. applications by status).
 * Filled by a database-side GROUP BY aggregate; never backed by an entity.
 *
 * @param groupName the grouping key (status, type, department code/name, ...)
 * @param count     the number of rows in the group
 */
public interface GroupCount {

    String getGroupName();

    long getCount();
}