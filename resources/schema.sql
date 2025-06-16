-- =====================================================
-- Network Protocol Analysis Tool - Complete Database
-- =====================================================
-- This script creates a comprehensive database for analyzing
-- network protocols across multiple layers (Transport, Encoding, Content)
-- 
-- Usage: sqlite3 netproto.db < this_script.sql
-- =====================================================

-- Database Configuration
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;

-- =====================================================
-- CORE TABLES
-- =====================================================

-- Core packet capture sessions
-- Represents individual pcap analysis sessions
CREATE TABLE sessions (
    session_id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    pcap_file_path TEXT,
    start_time DATETIME,
    end_time DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    wireshark_display_filter TEXT,
    total_packets INTEGER DEFAULT 0
);

-- Raw packet data from Wireshark/pcap
-- Individual network packets captured during sessions
CREATE TABLE packets (
    packet_id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    frame_number INTEGER NOT NULL,
    timestamp REAL NOT NULL,
    packet_size INTEGER NOT NULL,
    raw_data BLOB,
    wireshark_summary TEXT,
    FOREIGN KEY (session_id) REFERENCES sessions(session_id),
    UNIQUE(session_id, frame_number)
);

-- =====================================================
-- LAYERED ANALYSIS TABLES
-- =====================================================

-- TRANSPORT LAYER: Network-level packet analysis
-- TCP/UDP headers, IP addresses, ports, sequence numbers
CREATE TABLE transport_layer (
    transport_id INTEGER PRIMARY KEY AUTOINCREMENT,
    packet_id INTEGER NOT NULL,
    session_id INTEGER NOT NULL,
    protocol TEXT NOT NULL,
    src_ip TEXT NOT NULL,
    dst_ip TEXT NOT NULL,
    src_port INTEGER,
    dst_port INTEGER,
    tcp_flags TEXT,
    tcp_seq_num INTEGER,
    tcp_ack_num INTEGER,
    tcp_window_size INTEGER,
    udp_length INTEGER,
    checksum TEXT,
    payload_size INTEGER,
    FOREIGN KEY (packet_id) REFERENCES packets(packet_id),
    FOREIGN KEY (session_id) REFERENCES sessions(session_id)
);

-- ENCODING LAYER: Protocol encoding and structure analysis
-- HTTP headers, DNS queries, TLS handshakes, protocol-specific data
CREATE TABLE encoding_layer (
    encoding_id INTEGER PRIMARY KEY AUTOINCREMENT,
    transport_id INTEGER NOT NULL,
    session_id INTEGER NOT NULL,
    encoding_type TEXT NOT NULL,
    protocol_version TEXT,
    headers_raw BLOB,
    headers_parsed TEXT,
    body_raw BLOB,
    body_size INTEGER,
    compression_type TEXT,
    encryption_detected BOOLEAN DEFAULT FALSE,
    encoding_errors TEXT,
    FOREIGN KEY (transport_id) REFERENCES transport_layer(transport_id),
    FOREIGN KEY (session_id) REFERENCES sessions(session_id)
);

-- CONTENT LAYER: Application-level content analysis
-- Decoded payloads, extracted files, vulnerability detection
CREATE TABLE content_layer (
    content_id INTEGER PRIMARY KEY AUTOINCREMENT,
    encoding_id INTEGER NOT NULL,
    session_id INTEGER NOT NULL,
    content_type TEXT,
    decoded_content TEXT,
    content_hash TEXT,
    file_extracted BOOLEAN DEFAULT FALSE,
    file_path TEXT,
    vulnerabilities_detected TEXT,
    custom_analysis_results TEXT,
    suspicious_patterns TEXT,
    FOREIGN KEY (encoding_id) REFERENCES encoding_layer(encoding_id),
    FOREIGN KEY (session_id) REFERENCES sessions(session_id)
);

-- =====================================================
-- ANALYSIS AND TRACKING TABLES
-- =====================================================

-- Network flows/connections tracking
-- Aggregated connection state and statistics
CREATE TABLE flows (
    flow_id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    protocol TEXT NOT NULL,
    src_ip TEXT NOT NULL,
    dst_ip TEXT NOT NULL,
    src_port INTEGER,
    dst_port INTEGER,
    start_time REAL NOT NULL,
    end_time REAL,
    packet_count INTEGER DEFAULT 0,
    bytes_sent INTEGER DEFAULT 0,
    bytes_received INTEGER DEFAULT 0,
    flow_state TEXT,
    application_protocol TEXT,
    FOREIGN KEY (session_id) REFERENCES sessions(session_id),
    UNIQUE(session_id, src_ip, dst_ip, src_port, dst_port, protocol)
);

-- Custom analysis rules
-- User-defined patterns and detection logic
CREATE TABLE analysis_rules (
    rule_id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    rule_type TEXT NOT NULL,
    target_layer TEXT NOT NULL,
    pattern_match TEXT,
    lisp_function TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Results of applying analysis rules
-- Matches found by custom rules across all layers
CREATE TABLE rule_matches (
    match_id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_id INTEGER NOT NULL,
    packet_id INTEGER,
    transport_id INTEGER,
    encoding_id INTEGER,
    content_id INTEGER,
    matched_data TEXT,
    rule_result TEXT,
    severity TEXT,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (rule_id) REFERENCES analysis_rules(rule_id),
    FOREIGN KEY (packet_id) REFERENCES packets(packet_id),
    FOREIGN KEY (transport_id) REFERENCES transport_layer(transport_id),
    FOREIGN KEY (encoding_id) REFERENCES encoding_layer(encoding_id),
    FOREIGN KEY (content_id) REFERENCES content_layer(content_id)
);

-- =====================================================
-- PROTOCOL-SPECIFIC ANALYSIS TABLES
-- =====================================================

-- HTTP-specific analysis
-- Web traffic analysis with security assessments
CREATE TABLE http_analysis (
    http_id INTEGER PRIMARY KEY AUTOINCREMENT,
    encoding_id INTEGER NOT NULL,
    method TEXT,
    uri TEXT,
    version TEXT,
    status_code INTEGER,
    user_agent TEXT,
    referer TEXT,
    cookies TEXT,
    parameters TEXT,
    headers TEXT,
    response_time REAL,
    sql_injection_risk BOOLEAN DEFAULT FALSE,
    xss_risk BOOLEAN DEFAULT FALSE,
    path_traversal_risk BOOLEAN DEFAULT FALSE,
    FOREIGN KEY (encoding_id) REFERENCES encoding_layer(encoding_id)
);

-- TLS/SSL analysis
-- Encryption and certificate analysis
CREATE TABLE tls_analysis (
    tls_id INTEGER PRIMARY KEY AUTOINCREMENT,
    encoding_id INTEGER NOT NULL,
    version TEXT,
    cipher_suite TEXT,
    server_name TEXT,
    certificate_chain TEXT,
    handshake_complete BOOLEAN DEFAULT FALSE,
    weak_cipher BOOLEAN DEFAULT FALSE,
    cert_issues TEXT,
    FOREIGN KEY (encoding_id) REFERENCES encoding_layer(encoding_id)
);

-- File extraction and analysis
-- Files reconstructed from network traffic
CREATE TABLE extracted_files (
    file_id INTEGER PRIMARY KEY AUTOINCREMENT,
    content_id INTEGER NOT NULL,
    session_id INTEGER NOT NULL,
    filename TEXT,
    file_type TEXT,
    file_size INTEGER,
    file_hash TEXT,
    extraction_method TEXT,
    file_path TEXT,
    malware_detected BOOLEAN DEFAULT FALSE,
    analysis_results TEXT,
    FOREIGN KEY (content_id) REFERENCES content_layer(content_id),
    FOREIGN KEY (session_id) REFERENCES sessions(session_id)
);

-- =====================================================
-- PERFORMANCE INDEXES
-- =====================================================

CREATE INDEX idx_packets_session_time ON packets(session_id, timestamp);
CREATE INDEX idx_transport_endpoints ON transport_layer(src_ip, dst_ip, src_port, dst_port);
CREATE INDEX idx_flows_session ON flows(session_id, start_time);
CREATE INDEX idx_content_hash ON content_layer(content_hash);
CREATE INDEX idx_rule_matches_severity ON rule_matches(severity, timestamp);
CREATE INDEX idx_http_uri ON http_analysis(uri);
CREATE INDEX idx_encoding_type ON encoding_layer(encoding_type);

-- =====================================================
-- USEFUL VIEWS
-- =====================================================

-- Flow Summary View
-- Aggregated connection information with readable endpoint pairs
CREATE VIEW flow_summary AS
SELECT 
    f.flow_id,
    f.src_ip || ':' || f.src_port || ' -> ' || f.dst_ip || ':' || f.dst_port AS endpoint_pair,
    f.protocol,
    f.application_protocol,
    f.packet_count,
    f.bytes_sent + f.bytes_received AS total_bytes,
    (f.end_time - f.start_time) AS duration_seconds,
    s.name AS session_name
FROM flows f
JOIN sessions s ON f.session_id = s.session_id;

-- Security Alerts View
-- High-priority security findings across all sessions
CREATE VIEW security_alerts AS
SELECT 
    rm.match_id,
    rm.severity,
    ar.name AS rule_name,
    rm.matched_data,
    rm.timestamp,
    s.name AS session_name,
    t.src_ip,
    t.dst_ip
FROM rule_matches rm
JOIN analysis_rules ar ON rm.rule_id = ar.rule_id
LEFT JOIN transport_layer t ON rm.transport_id = t.transport_id
JOIN sessions s ON (rm.packet_id IN (SELECT packet_id FROM packets WHERE session_id = s.session_id))
WHERE rm.severity IN ('HIGH', 'CRITICAL')
ORDER BY rm.timestamp DESC;

-- Protocol Distribution View
-- Traffic breakdown by protocol type
CREATE VIEW protocol_distribution AS
SELECT 
    t.protocol,
    COUNT(*) as packet_count,
    SUM(t.payload_size) as total_bytes,
    AVG(t.payload_size) as avg_packet_size,
    s.name as session_name
FROM transport_layer t
JOIN sessions s ON t.session_id = s.session_id
GROUP BY t.protocol, s.session_id
ORDER BY total_bytes DESC;

-- =====================================================
-- SAMPLE DATA
-- =====================================================

-- Sample Sessions
INSERT INTO sessions (name, description, pcap_file_path, start_time, end_time, total_packets, wireshark_display_filter) VALUES
('Web_Traffic_Analysis', 'HTTP/HTTPS traffic capture', '/data/web_traffic.pcap', '2025-06-15 10:00:00', '2025-06-15 10:30:00', 1250, 'http or https'),
('DNS_Queries', 'DNS resolution analysis', '/data/dns_queries.pcap', '2025-06-15 11:00:00', '2025-06-15 11:15:00', 340, 'dns'),
('SSH_Connections', 'Encrypted SSH sessions', '/data/ssh_traffic.pcap', '2025-06-15 12:00:00', '2025-06-15 12:45:00', 890, 'tcp.port == 22');

-- Sample Packets
INSERT INTO packets (session_id, frame_number, timestamp, packet_size, wireshark_summary) VALUES
(1, 1, 1718445600.123456, 74, 'TCP 192.168.1.100:54321 → 93.184.216.34:80 [SYN]'),
(1, 2, 1718445600.125789, 66, 'TCP 93.184.216.34:80 → 192.168.1.100:54321 [SYN, ACK]'),
(1, 3, 1718445600.126001, 54, 'TCP 192.168.1.100:54321 → 93.184.216.34:80 [ACK]'),
(1, 4, 1718445600.127000, 512, 'HTTP GET /index.html HTTP/1.1'),
(1, 5, 1718445600.145000, 1434, 'HTTP/1.1 200 OK (text/html)'),
(2, 1, 1718449200.001000, 76, 'DNS Standard query A example.com'),
(2, 2, 1718449200.005000, 92, 'DNS Standard query response A 93.184.216.34'),
(3, 1, 1718452800.100000, 78, 'SSH-2.0-OpenSSH_8.9p1');

-- Sample Transport Layer Data
INSERT INTO transport_layer (packet_id, session_id, protocol, src_ip, dst_ip, src_port, dst_port, tcp_flags, tcp_seq_num, tcp_ack_num, tcp_window_size, payload_size) VALUES
(1, 1, 'TCP', '192.168.1.100', '93.184.216.34', 54321, 80, 'SYN', 3751234567, 0, 65535, 0),
(2, 1, 'TCP', '93.184.216.34', '192.168.1.100', 80, 54321, 'SYN,ACK', 2841234567, 3751234568, 28960, 0),
(3, 1, 'TCP', '192.168.1.100', '93.184.216.34', 54321, 80, 'ACK', 3751234568, 2841234568, 65535, 0),
(4, 1, 'TCP', '192.168.1.100', '93.184.216.34', 54321, 80, 'PSH,ACK', 3751234568, 2841234568, 65535, 458),
(5, 1, 'TCP', '93.184.216.34', '192.168.1.100', 80, 54321, 'PSH,ACK', 2841234568, 3751235026, 28960, 1380),
(6, 2, 'UDP', '192.168.1.100', '8.8.8.8', 53142, 53, NULL, NULL, NULL, NULL, 42),
(7, 2, 'UDP', '8.8.8.8', '192.168.1.100', 53, 53142, NULL, NULL, NULL, NULL, 58),
(8, 3, 'TCP', '192.168.1.100', '10.0.0.5', 52341, 22, 'PSH,ACK', 1234567890, 987654321, 65535, 24);

-- Sample Encoding Layer Data
INSERT INTO encoding_layer (transport_id, session_id, encoding_type, protocol_version, headers_parsed, body_size, compression_type, encryption_detected) VALUES
(4, 1, 'HTTP', '1.1', '{"Host": "example.com", "User-Agent": "Mozilla/5.0", "Accept": "text/html,application/xhtml+xml"}', 0, NULL, FALSE),
(5, 1, 'HTTP', '1.1', '{"Content-Type": "text/html", "Content-Length": "1270", "Server": "nginx/1.18.0"}', 1270, 'gzip', FALSE),
(6, 2, 'DNS', NULL, '{"query_type": "A", "query_name": "example.com", "query_class": "IN"}', 42, NULL, FALSE),
(7, 2, 'DNS', NULL, '{"response_code": "NOERROR", "answer_count": 1, "authority_count": 0}', 58, NULL, FALSE),
(8, 3, 'SSH', '2.0', '{"protocol": "SSH-2.0-OpenSSH_8.9p1", "comments": "Ubuntu-3ubuntu0.1"}', 24, NULL, TRUE);

-- Sample Content Layer Data
INSERT INTO content_layer (encoding_id, session_id, content_type, decoded_content, content_hash, vulnerabilities_detected) VALUES
(1, 1, 'application/x-www-form-urlencoded', 'GET /index.html HTTP/1.1', 'a1b2c3d4e5f67890abcdef1234567890abcdef12', '[]'),
(2, 1, 'text/html', '<!DOCTYPE html><html><head><title>Example</title></head><body><h1>Example Domain</h1></body></html>', 'b2c3d4e5f67890abcdef1234567890abcdef123a', '[]'),
(3, 2, 'application/dns', 'A query for example.com', 'c3d4e5f67890abcdef1234567890abcdef123ab2', '[]'),
(4, 2, 'application/dns', 'A response: example.com -> 93.184.216.34', 'd4e5f67890abcdef1234567890abcdef123ab2c3', '[]'),
(5, 3, 'application/ssh', 'SSH protocol negotiation', 'e5f67890abcdef1234567890abcdef123ab2c3d4', '[]');

-- Sample Flow Data
INSERT INTO flows (session_id, protocol, src_ip, dst_ip, src_port, dst_port, start_time, end_time, packet_count, bytes_sent, bytes_received, flow_state, application_protocol) VALUES
(1, 'TCP', '192.168.1.100', '93.184.216.34', 54321, 80, 1718445600.123456, 1718445600.150000, 5, 512, 1434, 'CLOSED', 'HTTP'),
(2, 'UDP', '192.168.1.100', '8.8.8.8', 53142, 53, 1718449200.001000, 1718449200.005000, 2, 42, 58, 'COMPLETED', 'DNS'),
(3, 'TCP', '192.168.1.100', '10.0.0.5', 52341, 22, 1718452800.100000, NULL, 1, 24, 0, 'ESTABLISHED', 'SSH');

-- Sample Analysis Rules
INSERT INTO analysis_rules (name, description, rule_type, target_layer, pattern_match, lisp_function, enabled) VALUES
('SQL_Injection_Detector', 'Detects potential SQL injection attacks', 'DETECT', 'CONTENT', '(?i)(union|select|drop|insert|update|delete).*from', '(detect-sql-injection content)', TRUE),
('XSS_Pattern_Matcher', 'Identifies cross-site scripting attempts', 'DETECT', 'CONTENT', '<script[^>]*>.*</script>', '(detect-xss-pattern content)', TRUE),
('Suspicious_User_Agent', 'Flags unusual user agent strings', 'DETECT', 'ENCODING', '(?i)(bot|crawler|scanner|sqlmap)', '(analyze-user-agent headers)', TRUE),
('Large_File_Transfer', 'Monitors large file transfers', 'DETECT', 'TRANSPORT', NULL, '(check-transfer-size payload-size)', TRUE);

-- Sample Rule Matches
INSERT INTO rule_matches (rule_id, encoding_id, matched_data, rule_result, severity) VALUES
(3, 1, 'User-Agent: Mozilla/5.0', 'Normal browser user agent detected', 'LOW'),
(4, 2, 'Content-Length: 1270', 'Small transfer detected', 'LOW');

-- Sample HTTP Analysis
INSERT INTO http_analysis (encoding_id, method, uri, version, status_code, user_agent, headers, response_time, sql_injection_risk, xss_risk) VALUES
(1, 'GET', '/index.html', '1.1', NULL, 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36', '{"Host": "example.com", "Accept": "text/html"}', NULL, FALSE, FALSE),
(2, NULL, NULL, '1.1', 200, NULL, '{"Content-Type": "text/html", "Server": "nginx/1.18.0"}', 0.018, FALSE, FALSE);

-- Sample TLS Analysis
INSERT INTO tls_analysis (encoding_id, version, cipher_suite, server_name, handshake_complete, weak_cipher) VALUES
(5, 'TLSv1.3', 'TLS_AES_256_GCM_SHA384', 'secure-server.example.com', TRUE, FALSE);

-- Sample Extracted Files
INSERT INTO extracted_files (content_id, session_id, filename, file_type, file_size, file_hash, extraction_method, malware_detected) VALUES
(2, 1, 'index.html', 'text/html', 1270, 'b2c3d4e5f67890abcdef1234567890abcdef123a', 'HTTP_RESPONSE', FALSE);

-- Optimize database after data insertion
ANALYZE;
VACUUM;

-- =====================================================
-- USEFUL QUERIES FOR NETWORK ANALYSIS
-- =====================================================

-- Query 1: Session Overview
-- Get basic statistics for all capture sessions
/*
SELECT 
    name,
    description,
    total_packets,
    datetime(start_time) as start_time,
    datetime(end_time) as end_time,
    round((julianday(end_time) - julianday(start_time)) * 24 * 60, 2) as duration_minutes
FROM sessions
ORDER BY start_time DESC;
*/

-- Query 2: Top Talkers by Bytes
-- Find the most active network endpoints
/*
SELECT 
    endpoint_pair,
    protocol,
    application_protocol,
    total_bytes,
    packet_count,
    round(duration_seconds, 2) as duration_sec,
    session_name
FROM flow_summary 
ORDER BY total_bytes DESC 
LIMIT 10;
*/

-- Query 3: Protocol Distribution
-- Analyze traffic composition by protocol
/*
SELECT 
    protocol,
    COUNT(*) as connections,
    SUM(total_bytes) as total_bytes,
    AVG(total_bytes) as avg_bytes_per_connection,
    MIN(total_bytes) as min_bytes,
    MAX(total_bytes) as max_bytes
FROM flow_summary
GROUP BY protocol
ORDER BY total_bytes DESC;
*/

-- Query 4: HTTP Traffic Analysis
-- Examine web traffic patterns and potential security issues
/*
SELECT 
    h.method,
    h.uri,
    h.status_code,
    h.user_agent,
    h.response_time,
    h.sql_injection_risk,
    h.xss_risk,
    s.name as session_name
FROM http_analysis h
JOIN encoding_layer e ON h.encoding_id = e.encoding_id
JOIN sessions s ON e.session_id = s.session_id
ORDER BY h.response_time DESC;
*/

-- Query 5: Security Threat Detection
-- Find all high-severity security matches
/*
SELECT 
    rule_name,
    severity,
    src_ip,
    dst_ip,
    matched_data,
    datetime(timestamp) as detection_time,
    session_name
FROM security_alerts
ORDER BY timestamp DESC;
*/

-- Query 6: Connection Timeline Analysis
-- Track connection establishment and termination patterns
/*
SELECT 
    datetime(f.start_time, 'unixepoch') as connection_start,
    datetime(f.end_time, 'unixepoch') as connection_end,
    f.src_ip || ':' || f.src_port as source,
    f.dst_ip || ':' || f.dst_port as destination,
    f.flow_state,
    f.application_protocol,
    f.bytes_sent + f.bytes_received as total_bytes
FROM flows f
JOIN sessions s ON f.session_id = s.session_id
ORDER BY f.start_time;
*/

-- Query 7: Encryption Usage Analysis
-- Identify encrypted vs unencrypted traffic
/*
SELECT 
    e.encoding_type,
    e.encryption_detected,
    COUNT(*) as packet_count,
    SUM(e.body_size) as total_payload_bytes,
    s.name as session_name
FROM encoding_layer e
JOIN sessions s ON e.session_id = s.session_id
GROUP BY e.encoding_type, e.encryption_detected, s.session_id
ORDER BY total_payload_bytes DESC;
*/

-- Query 8: File Extraction Summary
-- Review files extracted from network traffic
/*
SELECT 
    filename,
    file_type,
    file_size,
    extraction_method,
    malware_detected,
    s.name as source_session
FROM extracted_files ef
JOIN sessions s ON ef.session_id = s.session_id
ORDER BY file_size DESC;
*/

-- Query 9: DNS Query Analysis
-- Examine DNS resolution patterns
/*
SELECT 
    c.decoded_content,
    e.headers_parsed,
    datetime(p.timestamp, 'unixepoch') as query_time,
    t.src_ip as client_ip,
    t.dst_ip as dns_server
FROM content_layer c
JOIN encoding_layer e ON c.encoding_id = e.encoding_id
JOIN transport_layer t ON e.transport_id = t.transport_id
JOIN packets p ON t.packet_id = p.packet_id
WHERE e.encoding_type = 'DNS'
ORDER BY p.timestamp;
*/

-- Query 10: Performance Bottleneck Detection
-- Find slow connections and large transfers
/*
SELECT 
    f.src_ip || ':' || f.src_port || ' -> ' || f.dst_ip || ':' || f.dst_port as connection,
    f.application_protocol,
    f.bytes_sent + f.bytes_received as total_bytes,
    round(f.end_time - f.start_time, 3) as duration_seconds,
    round((f.bytes_sent + f.bytes_received) / (f.end_time - f.start_time), 0) as bytes_per_second,
    s.name as session_name
FROM flows f
JOIN sessions s ON f.session_id = s.session_id
WHERE f.end_time IS NOT NULL 
    AND (f.end_time - f.start_time) > 1.0  -- Connections longer than 1 second
ORDER BY bytes_per_second ASC
LIMIT 10;
*/
