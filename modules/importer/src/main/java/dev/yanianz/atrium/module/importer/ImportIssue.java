package dev.yanianz.atrium.module.importer;

/** One problem found while reading or writing CMI data. */
record ImportIssue(ImportCategory category, String subject, String message) {
    String format() {
        String where = subject == null || subject.isBlank() ? "-" : subject;
        return "[" + category.key() + "] " + where + ": " + message;
    }
}
