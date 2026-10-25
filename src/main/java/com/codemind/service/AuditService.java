package com.codemind.service;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuditService {
    public void record(String action, String detail) {
        System.out.printf("[AUDIT] %s action=%s detail=%s%n", Instant.now(), action, detail);
    }
}
