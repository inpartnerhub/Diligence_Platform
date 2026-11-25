/**
 * Full-text search over extracted document content incl. OCR (PRD DOC-06), backed by PostgreSQL full-text search.
 * Owns the index table and query mapping; authorization stays with the caller (the data room passes the folders a
 * user may see).
 */
@ApplicationModule(displayName = "Search", allowedDependencies = "shared")
package com.inpartnerhub.diligence.search;

import org.springframework.modulith.ApplicationModule;
