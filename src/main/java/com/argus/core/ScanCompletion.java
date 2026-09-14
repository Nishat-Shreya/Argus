package com.argus.core;

/** How a scan run terminated. The core-native terminal vocabulary; ScanArchive maps it onto
 *  db's ScanStatus. */
public enum ScanCompletion { COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED }
