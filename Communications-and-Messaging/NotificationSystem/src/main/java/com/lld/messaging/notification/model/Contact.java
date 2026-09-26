package com.lld.messaging.notification.model;

/** Where a user can be reached. Any field except id/name may be null (no phone, app not installed...). */
public record Contact(String userId, String name, String email, String phone, String pushToken) {
}
