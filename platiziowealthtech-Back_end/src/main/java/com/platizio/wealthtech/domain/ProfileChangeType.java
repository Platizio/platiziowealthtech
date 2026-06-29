package com.platizio.wealthtech.domain;

/**
 * What a profile-change challenge represents (investor.md R9/R10):
 * the distributor's first fill of a skipped profile, or a later edit to a READY one.
 */
public enum ProfileChangeType {
    INITIAL_DISTRIBUTOR_FILL,
    PROFILE_EDIT
}
