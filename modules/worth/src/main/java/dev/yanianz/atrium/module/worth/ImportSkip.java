package dev.yanianz.atrium.module.worth;

/** One price line that cannot be imported, with the reason shown in the report. */
record ImportSkip(String origin, String reason, String detail) {
}
