package org.openl.rules.webstudio.web.repository;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.openl.rules.webstudio.web.repository.tree.AbstractTreeNode;
import org.openl.rules.webstudio.web.repository.tree.TreeDProject;
import org.openl.rules.webstudio.web.repository.tree.TreeFile;
import org.openl.rules.webstudio.web.repository.tree.TreeFolder;
import org.openl.rules.webstudio.web.repository.tree.TreeProject;
import org.openl.rules.webstudio.web.repository.tree.TreeRepository;
import org.openl.rules.workspace.abstracts.DeploymentDescriptorProject;
import org.openl.rules.workspace.abstracts.Project;
import org.openl.rules.workspace.abstracts.ProjectArtefact;
import org.openl.rules.workspace.abstracts.ProjectFolder;
import org.openl.rules.workspace.dtr.RepositoryException;
import org.openl.rules.workspace.uw.UserWorkspace;
import org.openl.rules.workspace.uw.UserWorkspaceDeploymentProject;
import org.openl.rules.workspace.uw.UserWorkspaceProject;
import org.openl.util.filter.OpenLFilter;
import org.openl.util.filter.AllOpenLFilter;
import org.richfaces.component.UITree;

import org.richfaces.event.NodeSelectedEvent;

import org.richfaces.model.TreeNode;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

/**
 * Used for holding information about rulesRepository tree.
 *
 * @author Andrey Naumenko
 */
public class RepositoryTreeState {
    private static final Log LOG = LogFactory.getLog(RepositoryTreeState.class);

    /** Root node for RichFaces's tree. It is not displayed. */
    private TreeRepository root;
    private AbstractTreeNode selectedNode;
    private TreeRepository rulesRepository;
    private TreeRepository deploymentRepository;
    private UserWorkspace userWorkspace;
    private OpenLFilter filter = AllOpenLFilter.INSTANCE;

    public Boolean adviseNodeSelected(UITree uiTree) {
        AbstractTreeNode node = (AbstractTreeNode) uiTree.getRowData();

        ProjectArtefact projectArtefact = node.getDataBean();
        ProjectArtefact selected = selectedNode.getDataBean();

        if ((selected == null) || (projectArtefact == null)) {
            return selectedNode.getId().equals(node.getId());
        }

        if (selected.getArtefactPath().equals(projectArtefact.getArtefactPath())) {
            if (projectArtefact instanceof DeploymentDescriptorProject) {
                return selected instanceof DeploymentDescriptorProject;
            }
            return true;
        }
        return false;
    }
    
    private void buildTree() {
        if (root != null) {
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Starting buildTree()");
        }

        root = new TreeRepository("", "", "root");

        String rpName = "Rules Projects";
        rulesRepository = new TreeRepository(rpName, rpName, UiConst.TYPE_REPOSITORY);
        rulesRepository.setDataBean(null);

        String dpName = "Deployment Projects";
        deploymentRepository = new TreeRepository(dpName, dpName, UiConst.TYPE_DEPLOYMENT_REPOSITORY);
        deploymentRepository.setDataBean(null);

        root.add(rulesRepository);
        root.add(deploymentRepository);

        Collection<UserWorkspaceProject> rulesProjects = userWorkspace.getProjects();

        OpenLFilter filter = this.filter;
        for (UserWorkspaceProject project : rulesProjects) {
            if (!(filter.supports(Project.class) && !filter.select(project))) {
                addRulesProjectToTree(project);
            }
        }

        List<UserWorkspaceDeploymentProject> deploymentsProjects = null;

        try {
            deploymentsProjects = userWorkspace.getDDProjects();
        } catch (RepositoryException e) {
            LOG.error("Cannot get deployment projects", e);
        }

        for (UserWorkspaceDeploymentProject project : deploymentsProjects) {
            addDeploymentProjectToTree(project);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Finishing buildTree()");
        }

        if (selectedNode == null) {
            selectedNode = rulesRepository;
        } else {
            updateSelectedNode();
        }
    }
    
    public TreeRepository getDeploymentRepository() {
        buildTree();
        return deploymentRepository;
    }

    public OpenLFilter getFilter() {
        return filter;
    }

    public TreeRepository getRoot() {
        buildTree();
        return root;
    }

    public TreeRepository getRulesRepository() {
        buildTree();
        return rulesRepository;
    }

    public AbstractTreeNode getSelectedNode() {
        buildTree();
        return selectedNode;
    }

    public UserWorkspaceProject getSelectedProject() {
        ProjectArtefact artefact = getSelectedNode().getDataBean();
        if (artefact instanceof UserWorkspaceProject) {
            return (UserWorkspaceProject) artefact;
        }
        return null;
    }

    public void invalidateSelection() {
        selectedNode = rulesRepository;
    }
    
    /**
     * Refreshes repositoryTreeState.selectedNode after rebuilding tree.
     */
    public void updateSelectedNode() {
        Iterator<String> it = getSelectedNode().getDataBean().getArtefactPath().getSegments().iterator();
        AbstractTreeNode currentNode = getRulesRepository();
        while ((currentNode != null) && it.hasNext()) {
            currentNode = currentNode.getChild(it.next());
        }

        if (currentNode != null) {
            selectedNode = (AbstractTreeNode) currentNode;
        }
    }

    public void refreshNode(AbstractTreeNode node){
        if (!node.isLeaf()) {
            node.removeChildren();
            TreeFolder folder = (TreeFolder) node;
            traverseFolder(folder, ((ProjectFolder) folder.getDataBean()).getArtefacts(), filter);
        }
    }
    
    public void deleteNode(AbstractTreeNode node){
        node.getParent().removeChild(node.getId());
    }
    
    public void deleteSelectedNodeFromTree(){
        deleteNode(selectedNode);
        invalidateSelection();
    }
    
    public void addDeploymentProjectToTree(UserWorkspaceDeploymentProject project) {
        TreeDProject prj = new TreeDProject(project.getName(), project.getName());
        prj.setDataBean(project);
        deploymentRepository.add(prj);
    }

    public void addRulesProjectToTree(UserWorkspaceProject project) {
        TreeProject prj = new TreeProject(project.getName(), project.getName());
        prj.setDataBean(project);
        rulesRepository.add(prj);
        traverseFolder(prj, project.getArtefacts(), filter);
    }

    public void addNodeToTree(AbstractTreeNode parent, ProjectArtefact childArtefact) {
        String id = childArtefact.getName();
        if (childArtefact.isFolder()) {
            TreeFolder treeFolder = new TreeFolder(id, childArtefact.getName());
            treeFolder.setDataBean(childArtefact);
            parent.add(treeFolder);
            traverseFolder(treeFolder, ((ProjectFolder) childArtefact).getArtefacts(), filter);
        } else {
            TreeFile treeFile = new TreeFile(id, childArtefact.getName());
            treeFile.setDataBean(childArtefact);
            parent.add(treeFile);
        }
    }

    /**
     * Forces tree rebuild during next access.
     */
    public void invalidateTree() {
        root = null;
    }
    
    /**
     * Moves selection to the parent of the current selected node.
     */
    public void moveSelectionToParentNode() {
        if (selectedNode.getParent() instanceof AbstractTreeNode) {
            selectedNode = (AbstractTreeNode) selectedNode.getParent();
        } else {
            invalidateSelection();
        }
    }

    public void processSelection(NodeSelectedEvent event) {
        UITree tree = (UITree) event.getComponent();
        
        try {
            selectedNode = (AbstractTreeNode) tree.getRowData();
        } catch (IllegalStateException ex) {
            // If nothing selected in tree then invalidate selection. 
            selectedNode = getSelectedNode();
        }
    }

    /**
     * Refreshes repositoryTreeState.selectedNode.
     */
    public void refreshSelectedNode() {
        refreshNode(selectedNode);
    }

    public void setFilter(OpenLFilter filter) {
        this.filter = filter != null ? filter : AllOpenLFilter.INSTANCE;
        root = null;
    }

    public void setRoot(TreeRepository root) {
        this.root = root;
    }

    public void setSelectedNode(AbstractTreeNode selectedNode) {
        this.selectedNode = selectedNode;
    }

    public void setUserWorkspace(UserWorkspace userWorkspace) {
        this.userWorkspace = userWorkspace;
    }

    public void traverseFolder(TreeFolder folder, Collection<? extends ProjectArtefact> artefacts, OpenLFilter filter) {

        Collection<ProjectArtefact> filteredArtefacts = new ArrayList<ProjectArtefact>();
        for (ProjectArtefact artefact : artefacts) {
            if (!(filter.supports(artefact.getClass()) && !filter.select(artefact))) {
                filteredArtefacts.add(artefact);
            }
        }

        ProjectArtefact[] sortedArtefacts = new ProjectArtefact[filteredArtefacts.size()];
        sortedArtefacts = filteredArtefacts.toArray(sortedArtefacts);

        Arrays.sort(sortedArtefacts, RepositoryUtils.ARTEFACT_COMPARATOR);

        for (ProjectArtefact artefact : sortedArtefacts) {
            addNodeToTree(folder, artefact);
        }
    }

}
