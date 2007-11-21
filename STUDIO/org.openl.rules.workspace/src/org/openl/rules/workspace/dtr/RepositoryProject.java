package org.openl.rules.workspace.dtr;

import org.openl.rules.repository.CommonUser;
import org.openl.rules.workspace.WorkspaceUser;
import org.openl.rules.workspace.abstracts.Project;
import org.openl.rules.workspace.abstracts.ProjectException;

public interface RepositoryProject extends Project, RepositoryProjectFolder {
    /**
     * Locks the project in DTR.
     * @param user
     * @throws ProjectException
     */
    void lock(WorkspaceUser user) throws ProjectException;
    
    /**
     * Unlocks the project in DTR.
     * 
     * @param user
     * @throws ProjectException
     */
    void unlock(WorkspaceUser user) throws ProjectException;
    
    /**
     * Mark the project for deletion.
     * 
     * @throws ProjectException
     */
    void delete(CommonUser user) throws ProjectException;
    
    /**
     * Unmark the project from deletion.
     * 
     * @throws ProjectException
     */
    void undelete(CommonUser user) throws ProjectException;
    
    /**
     * Erase the project from DTR. All data will be lost.
     * Before erasing the project must be marked for deletion.
     * 
     * @throws ProjectException
     */
    void erase(CommonUser user) throws ProjectException;
    
    /**
     * Checks whether the project is marked for deletion.
     * @return
     */
    boolean isMarkedForDeletion();

    /**
     * Checks whether the project is
     * @return
     */
    boolean isLocked();
    
    /**
     * Gets information on current lock.
     * 
     * @return
     */
    LockInfo getlLockInfo();
    
    void commit(Project source, CommonUser user) throws ProjectException;
}
