package br.com.easyaudit.platform.tenant;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import org.hibernate.annotations.TenantId;

@Entity
class Note {

    @Id
    @GeneratedValue
    UUID id;

    @TenantId
    UUID tenantId;

    String text;

    protected Note() {
    }

    Note(String text) {
        this.text = text;
    }
}
