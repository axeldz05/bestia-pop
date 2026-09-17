---
name: bestiapop-crashlytics
description: >-
  Guide for connecting to Firebase Crashlytics via the firebase-mcp-server,
  querying issues and reports for BestiaPop, and inspecting event stack traces,
  logs, and custom keys.
---

# BestiaPop — Firebase Crashlytics Diagnostics

Reference guide for diagnosing crashes, ANRs, and non-fatal exceptions in BestiaPop using the `firebase-mcp-server`.

## 1. Project Identifiers

Strictly required parameters for all Crashlytics MCP tool calls:

| Parameter | Value |
|---|---|
| **Firebase Project ID** | `bestia-pop` |
| **Project Number** | `562715538455` |
| **Android App ID** (`appId`) | `1:562715538455:android:389c041c06223639cc5852` |
| **Package Name** | `com.bestiapop.android` |

---

## 2. Authentication & Environment Setup

Verify connection and active project before querying Crashlytics:

1. **Check environment:**
   ```json
   call_mcp_tool(
     ServerName: "firebase-mcp-server",
     ToolName: "firebase_get_environment",
     Arguments: {}
   )
   ```
2. **If unauthenticated (`Authenticated User: <NONE>`):**
   - Call `firebase_login` with empty arguments:
     ```json
     call_mcp_tool(
       ServerName: "firebase-mcp-server",
       ToolName: "firebase_login",
       Arguments: {}
     )
     ```
   - Present login URL and Session ID to the user with a security warning to verify matching Session ID.
   - When user provides the auth code:
     ```json
     call_mcp_tool(
       ServerName: "firebase-mcp-server",
       ToolName: "firebase_login",
       Arguments: { "authCode": "<USER_AUTH_CODE>" }
     )
     ```
3. **Set active project:**
   ```json
   call_mcp_tool(
     ServerName: "firebase-mcp-server",
     ToolName: "firebase_update_environment",
     Arguments: {
       "active_project": "bestia-pop",
       "project_dir": "/home/drusila/Projects/sofoapps"
     }
   )
   ```

---

## 3. Querying Reports & Issues

> [!NOTE]
> Tool name is `crashlytics_get_report` (not `firebase_get_report`).

### A. List Top Versions
Find exact version display strings (e.g. `"1.0-beta.11 (12)"`):
```json
call_mcp_tool(
  ServerName: "firebase-mcp-server",
  ToolName: "crashlytics_get_report",
  Arguments: {
    "appId": "1:562715538455:android:389c041c06223639cc5852",
    "report": "topVersions"
  }
)
```

### B. List Top Issues for a Version
Filter by version display name and error type (`FATAL`, `NON_FATAL`, `ANR`):
```json
call_mcp_tool(
  ServerName: "firebase-mcp-server",
  ToolName: "crashlytics_get_report",
  Arguments: {
    "appId": "1:562715538455:android:389c041c06223639cc5852",
    "report": "topIssues",
    "filter": {
      "versionDisplayNames": ["1.0-beta.11 (12)"],
      "issueErrorTypes": ["NON_FATAL"]
    }
  }
)
```

---

## 4. Deep Issue Inspection

### A. Get Issue Details
```json
call_mcp_tool(
  ServerName: "firebase-mcp-server",
  ToolName: "crashlytics_get_issue",
  Arguments: {
    "appId": "1:562715538455:android:389c041c06223639cc5852",
    "issueId": "<ISSUE_ID_HEX>"
  }
)
```

### B. Batch Get Sample Events (Stack Traces & Logs)
Use the `sampleEvent` URI obtained from the issue report:
```json
call_mcp_tool(
  ServerName: "firebase-mcp-server",
  ToolName: "crashlytics_batch_get_events",
  Arguments: {
    "appId": "1:562715538455:android:389c041c06223639cc5852",
    "names": ["<SAMPLE_EVENT_RESOURCE_NAME>"]
  }
)
```
Inspect:
- `exceptions`: Cause chain and stack trace frames.
- `logs`: Chronological breadcrumbs (`[BestiaPopPlayback]`, `[BestiaPopService]`).
- `customKeys`: `last_app_state`, `last_playback`, `exit_reason`, memory usage (`pss_mb`, `rss_mb`).

### C. List More Events for an Issue
```json
call_mcp_tool(
  ServerName: "firebase-mcp-server",
  ToolName: "crashlytics_list_events",
  Arguments: {
    "appId": "1:562715538455:android:389c041c06223639cc5852",
    "filter": {
      "issueId": "<ISSUE_ID_HEX>",
      "versionDisplayNames": ["1.0-beta.11 (12)"]
    },
    "pageSize": 5
  }
)
```
