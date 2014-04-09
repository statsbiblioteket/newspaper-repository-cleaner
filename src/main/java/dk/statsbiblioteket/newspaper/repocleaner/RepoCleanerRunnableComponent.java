package dk.statsbiblioteket.newspaper.repocleaner;

import dk.statsbiblioteket.doms.central.connectors.BackendInvalidCredsException;
import dk.statsbiblioteket.doms.central.connectors.BackendInvalidResourceException;
import dk.statsbiblioteket.doms.central.connectors.BackendMethodFailedException;
import dk.statsbiblioteket.doms.central.connectors.EnhancedFedora;
import dk.statsbiblioteket.medieplatform.autonomous.Batch;
import dk.statsbiblioteket.medieplatform.autonomous.ResultCollector;
import dk.statsbiblioteket.medieplatform.autonomous.TreeProcessorAbstractRunnableComponent;
import dk.statsbiblioteket.medieplatform.autonomous.iterator.eventhandlers.EventRunner;
import dk.statsbiblioteket.medieplatform.autonomous.iterator.eventhandlers.TreeEventHandler;
import org.slf4j.LoggerFactory;

import javax.mail.MessagingException;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * When invoked on a batch, retrieve all batches with same id and a lower roundtripnumber. TreeIterate each of these
 * collection the pids and jp2 file path. Delete the objects from DOMS. Then send a mail with all the file paths
 * to designated recipients.
 */
public class RepoCleanerRunnableComponent extends TreeProcessorAbstractRunnableComponent {

    private final EnhancedFedora eFedora;
    private final SimpleMailer simpleMailer;
    private List<String> fileDeletionsrecipients;
    private String fileDeletionSubject;
    private String fileDeletionBody;


    private String comment;
    private String relationPredicate;
    private static org.slf4j.Logger log = LoggerFactory.getLogger(RepoCleanerRunnableComponent.class);


    protected RepoCleanerRunnableComponent(Properties properties, EnhancedFedora eFedora) {
        super(properties);
        this.eFedora = eFedora;
        simpleMailer = setupMailer(properties);
        fileDeletionSubject = properties.getProperty(ConfigConstants.SUBJECT_PATTERN);
        fileDeletionBody = properties.getProperty(ConfigConstants.BODY_PATTERN);
        comment = properties.getProperty(ConfigConstants.DOMS_COMMIT_COMMENT);
        relationPredicate = properties.getProperty(
                ConfigConstants.RELATION, "info:fedora/fedora-system:def/relations-external#hasPart");

        fileDeletionsrecipients = Arrays.asList(
                properties.getProperty(ConfigConstants.ALERT_EMAIL_ADDRESSES).split("\\s*,\\s*")
                                               );

    }

    private SimpleMailer setupMailer(Properties properties) {
        return new SimpleMailer(
                properties.getProperty(ConfigConstants.EMAIL_FROM_ADDRESS),
                properties.getProperty(ConfigConstants.SMTP_HOST),
                properties.getProperty(ConfigConstants.SMTP_PORT));

    }

    @Override
    public String getEventID() {
        return getProperties().getProperty(ConfigConstants.EVENT_ID);
    }

    @Override
    public void doWorkOnBatch(Batch batch, ResultCollector resultCollector) throws Exception {
        Integer roundTrip = batch.getRoundTripNumber();
        if (roundTrip > 1) {
            //TODO what if several identical batches??
            String batchObjectPid = eFedora.findObjectFromDCIdentifier("path:B" + batch.getBatchID()).get(0);
            for (int i = 1; i < roundTrip; i++) {

                //TODO think about storing intermediate values, such as the fileset or pidset
                Batch oldBatch = new Batch(batch.getBatchID(), i);
                Collection<String> files = cleanRoundTrip(oldBatch, resultCollector, batchObjectPid);
                reportFiles(oldBatch, batch, files);
            }
        }
    }

    private Collection<String> cleanRoundTrip(Batch batch, ResultCollector resultCollector, String batchObjectPid) throws
                                                                                                            IOException,
                                                                                                            BackendMethodFailedException,
                                                                                                            BackendInvalidCredsException,
                                                                                                            BackendInvalidResourceException {
        if (eFedora.findObjectFromDCIdentifier("path:" + batch.getFullID()).isEmpty()) {
            return Collections.emptyList();
        }//Not empty, so there is a a round trip to delete

        CollectorHandler collectorHandler = new CollectorHandler();
        List<TreeEventHandler> handlers = Arrays.asList((TreeEventHandler) collectorHandler);
        EventRunner eventRunner = new EventRunner(createIterator(batch));
        eventRunner.runEvents(handlers, resultCollector);


        //TODO try finally, to make sure the mails are sent??
        deleteRoundTrip(batchObjectPid, collectorHandler.getRoundTripPid(), collectorHandler.getPids());
        return collectorHandler.getFiles();
    }

    /**
     * Delete the batch from the metadata repository. Will delete all the "pids" and will remove the "relation" from
     * "batchObjectPid" to "roundTripObjectPid"
     *
     * @param batchObjectPid     the pid of the batch object
     * @param roundTripObjectPid the pid of the round trip object
     * @param pids               the pids to delete.
     *
     * @throws BackendMethodFailedException    Stuff failed
     * @throws BackendInvalidResourceException Deleting something that does not exist?
     * @throws BackendInvalidCredsException    invalid credentials
     * @see #relationPredicate
     * @see #comment
     */
    protected void deleteRoundTrip(String batchObjectPid, String roundTripObjectPid, Iterable<String> pids) throws
                                                                                                        BackendMethodFailedException,
                                                                                                        BackendInvalidCredsException,
                                                                                                        BackendInvalidResourceException {

        for (String pid : pids) {
            try {
                eFedora.deleteObject(pid, comment);
            } catch (BackendInvalidResourceException e) {
                log.warn("Failed to delete object {}",pid,e);
            }
        }
        eFedora.deleteRelation(batchObjectPid, null, relationPredicate, roundTripObjectPid, false, comment);

    }

    /**
     * Send a mail to the recipients about the approval of the batch and the resulting deletion of the older batch
     *
     * @param oldBatch the old batch which is to be deleted
     * @param batch    the batch that have been approved
     * @param files    the files in the old batch which should be deleted
     *
     * @throws MessagingException if sending the mail failed
     * @see #fileDeletionsrecipients
     * @see #formatSubject(String, dk.statsbiblioteket.medieplatform.autonomous.Batch,
     * dk.statsbiblioteket.medieplatform.autonomous.Batch)
     * @see #formatBody(String, dk.statsbiblioteket.medieplatform.autonomous.Batch, dk.statsbiblioteket.medieplatform.autonomous.Batch, java.util.Collection)
     */
    protected void reportFiles(Batch oldBatch, Batch batch, Collection<String> files) throws MessagingException {
        if (!files.isEmpty()) {
            simpleMailer.sendMail(
                    fileDeletionsrecipients,
                    formatSubject(fileDeletionSubject, oldBatch, batch),
                    formatBody(fileDeletionBody, oldBatch, batch, files));
        }
    }

    /**
     * Format the body of the mail
     *
     * @param fileDeletionBody The pattern for the mail body
     * @param oldBatch         the old batch which is to be deleted
     * @param batch            the batch that have been approved
     * @param files            the files in the old batch which should be deleted
     *
     * @return the body as a string
     * @see #formatFiles(java.util.Collection)
     */
    protected static String formatBody(String fileDeletionBody, Batch oldBatch, Batch batch, Collection<String> files) {
        return MessageFormat.format(
                fileDeletionBody,
                batch.getBatchID(),
                batch.getRoundTripNumber(),
                oldBatch.getRoundTripNumber(),
                formatFiles(files));
        //TODO this set is BIG, does it work??
    }

    /**
     * Format the set to of files as a string. Will perform the "/" to "_" nessesary when working with the
     * bitrepository
     *
     * @param files the files
     *
     * @return the set of files as a string
     */
    protected static String formatFiles(Collection<String> files) {
        StringBuilder result = new StringBuilder();
        for (String file : files) {
            result.append("\n").append(file.replaceAll("/", "_"));
        }
        return result.toString();
    }

    /**
     * Format the subject for the message
     *
     * @param fileDeletionSubject the pattern for the subject
     * @param oldBatch            the old batch which is to be deleted
     * @param batch               the batch that have been approved
     *
     * @return the subject as a string
     */
    protected static String formatSubject(String fileDeletionSubject, Batch oldBatch, Batch batch) {
        return MessageFormat.format(
                fileDeletionSubject, batch.getBatchID(), batch.getRoundTripNumber(), oldBatch.getRoundTripNumber());
    }

}