package org.dspace.workflow;

import org.apache.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.AuthorizeManager;
import org.dspace.content.*;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.LogManager;
import org.dspace.eperson.Group;
import org.dspace.handle.HandleManager;
import org.dspace.identifier.IdentifierService;
import org.dspace.submit.AbstractProcessingStep;
import org.dspace.utils.DSpace;
import org.dspace.workflow.actions.Action;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * User: kevin (kevin at atmire.com)
 * Date: 18-aug-2010
 * Time: 9:09:31
 *
 * Misc methods to support the dryad data model
 */
public class DryadWorkflowUtils {

    private static Logger log = Logger.getLogger(DryadWorkflowUtils.class);


    public static boolean isDataPackage(WorkflowItem wfi){
        return wfi.getCollection().getHandle().equals(ConfigurationManager.getProperty("submit.publications.collection"));
    }

    public static boolean isDataPackageArchived(Context context, WorkflowItem wfi) throws SQLException {
        Item dataPackage = getDataPackage(context, wfi.getItem());
        return dataPackage != null && dataPackage.isArchived();
    }


    public static Item getDataPackage(Context context, Item dataFile){
        DCValue[] dataPackageUrl = dataFile.getMetadata(MetadataSchema.DC_SCHEMA, "relation", "ispartof", Item.ANY);

        if(0 < dataPackageUrl.length){
            try{
                IdentifierService service = new DSpace().getSingletonService(IdentifierService.class);
                Item dataPackage = (Item) service.resolve(context, dataPackageUrl[0].value);
                //Perhaps we are dealing with a workspaceitem, so try to resolve by id
                if(dataPackage == null)
                    dataPackage = WorkspaceItem.find(context, Integer.parseInt(dataPackageUrl[0].value)).getItem();

                return dataPackage;
            } catch (Exception e){
                log.error(LogManager.getHeader(context, "Error while retrieving data package", "datafile: " + dataFile.getID()), e);
            }
        }
        return null;
    }

    public static Item[] getDataFiles(Context context, Item dataPackage) throws SQLException {
        DCValue[] dataFileUrls = dataPackage.getMetadata(MetadataSchema.DC_SCHEMA, "relation", "haspart", Item.ANY);
        List<Item> result = new ArrayList<Item>();
        
        for (DCValue dataFileUrl : dataFileUrls) {
            try{
                IdentifierService service = new DSpace().getSingletonService(IdentifierService.class);

                Item datasetItem = (Item) service.resolve(context, dataFileUrl.value);
                if(datasetItem == null){
                    WorkspaceItem wsi = WorkspaceItem.find(context, Integer.parseInt(dataFileUrl.value));
                    if(wsi != null)
                        datasetItem = wsi.getItem();
                }
                result.add(datasetItem);
            } catch (Exception e){
                log.error(LogManager.getHeader(context, "Error while retrieving data files", "datapackage: " + dataPackage.getID()), e);
            }
        }
        return result.toArray(new Item[result.size()]);
    }

    public static void grantCuratorReadRightsOnItem(Context c, WorkflowItem wfi, Action action) throws SQLException, AuthorizeException {

        //Make sure that our curators have read (and only read rights)
        Role curatorRole = action.getParent().getStep().getWorkflow().getRoles().get("curator");
        Group curators = WorkflowUtils.getRoleGroup(c, wfi.getCollection().getID(), curatorRole);
        grantReadRightsToItem(c, wfi.getItem(), curators);
        //Also do this for all the data files
        Item[] dataFiles = DryadWorkflowUtils.getDataFiles(c, wfi.getItem());
        for (Item dataFile : dataFiles) {
            grantReadRightsToItem(c, dataFile, curators);
        }
    }

    private static void grantReadRightsToItem(Context c, Item item, Group curators) throws SQLException, AuthorizeException {
        AuthorizeManager.addPolicy(c, item, Constants.READ, curators);

        //Also add it for the bitstreams & the data sets
        AuthorizeManager.addPolicy(c ,item, Constants.READ, curators);
        Bundle[] bundles = item.getBundles();
        for (Bundle bundle : bundles) {
            AuthorizeManager.addPolicy(c ,bundle, Constants.READ, curators);
            Bitstream[] bits = bundle.getBitstreams();
            for (Bitstream bit : bits) {
                AuthorizeManager.addPolicy(c, bit, Constants.READ, curators);
            }

        }
    }

    public static String createDataset(Context context, HttpServletRequest request, Item publication, boolean fromWorkflow) throws SQLException, AuthorizeException, IOException {
        Collection dataCollection = (Collection) HandleManager.resolveToObject(context, ConfigurationManager.getProperty("submit.dataset.collection"));

        //We need to add another dataset so create an item for it
        WorkspaceItem newDataFileItem = WorkspaceItem.create(context, dataCollection, true);

        newDataFileItem.getItem().setSubmitter(context.getCurrentUser());
        String id;
        DCValue[] doiIdentifiers = publication.getMetadata(MetadataSchema.DC_SCHEMA, "identifier", null, Item.ANY);
        if(0 < doiIdentifiers.length)
            id = doiIdentifiers[0].value;
        else
            id = HandleManager.resolveToURL(context, publication.getHandle());

        //Get the publication this dataset is part of
        newDataFileItem.getItem().addMetadata(MetadataSchema.DC_SCHEMA, "relation", "ispartof", null, id);
        AbstractProcessingStep.inheritMetadata(publication, newDataFileItem.getItem());

        //If we come from the workflow make sure our datafileitem is aware of it
        if(fromWorkflow){
            newDataFileItem.getItem().addMetadata(WorkflowRequirementsManager.WORKFLOW_SCHEMA, "submit", "toworkflow", null, Boolean.TRUE.toString());
        }

        newDataFileItem.update();

        return request.getContextPath() + "/submit?workspaceID=" + newDataFileItem.getID() + "&skipOverview=true";

    }


}
