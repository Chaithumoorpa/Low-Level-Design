package com.lld.management.tasks.model;

/** A person on the team. Managers may change any task; members only their own. */
public record User(String id, String name, Role role) {

    public enum Role {
        MEMBER, MANAGER
    }

    public static User member(String id, String name) {
        return new User(id, name, Role.MEMBER);
    }

    public static User manager(String id, String name) {
        return new User(id, name, Role.MANAGER);
    }

    public boolean isManager() {
        return role == Role.MANAGER;
    }

    @Override
    public String toString() {
        return name;
    }
}
