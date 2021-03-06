package dk.statsbiblioteket.newspaper.repocleaner;

public class ConfigConstants {

    /**
     * A comma-separated list of email addresses to which alert messages will be sent.
     */
    public static final String ALERT_EMAIL_ADDRESSES = "approve-delete.email.addresses";
    /**
     * The smtp host.
     */
    public static final String SMTP_HOST = "smtp.host";

    /**
     * The smtp port.
     */
    public static final String SMTP_PORT = "smtp.port";

    /**
     * The "from" email address.
     */
    public static final String EMAIL_FROM_ADDRESS = "approve-delete.email.from.address";

    public static final java.lang.String SUBJECT_PATTERN = "approve-delete.email.subject.pattern";
    public static final java.lang.String BODY_PATTERN = "approve-delete.email.body.pattern";
    public static final java.lang.String EVENT_ID = "autonomous.eventID";
    public static final java.lang.String DOMS_COMMIT_COMMENT = "approve-delete.doms.commit.comment";
    public static final java.lang.String RELATION = "approve-delete.doms.batch.to.roundtrip.relation";
}
