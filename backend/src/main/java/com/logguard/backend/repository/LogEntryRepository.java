package com.logguard.backend.repository;

import com.logguard.backend.model.LogEntry;
import com.logguard.backend.model.LogLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface LogEntryRepository extends JpaRepository<LogEntry, Long> {
    List<LogEntry> findBySourceAndLevelInAndTimestampAfter(String source, List<LogLevel> levels, LocalDateTime after);
    List<LogEntry> findByMessageAndTimestampAfter(String message, LocalDateTime after);
    List<LogEntry> findByTimestampAfterOrderByTimestampDesc(LocalDateTime after);
}
