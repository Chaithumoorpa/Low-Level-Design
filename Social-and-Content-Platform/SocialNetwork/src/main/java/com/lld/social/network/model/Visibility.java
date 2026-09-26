package com.lld.social.network.model;

/** Who may see a post. Blocking overrides everything: a blocked user sees nothing either way. */
public enum Visibility {
    /** Anyone, including followers who aren't friends. */
    PUBLIC,
    /** Friends only. */
    FRIENDS,
    /** Just the author (a draft or private note). */
    ONLY_ME
}
