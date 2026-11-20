/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.render;

import net.minecraft.client.Minecraft;
import org.apache.commons.lang3.Validate;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayList;

public class GlQueryObject {
    private int idQueryObject = -1;
    private boolean isQuerying = false;
    private boolean hasResult = false;
    
    public GlQueryObject(int handle) {
        this.idQueryObject = handle;
    }
    
    public void performQueryAnySamplePassed(Runnable renderingFunc) {
        // mac does not support any samples passed query
        if (Minecraft.ON_OSX) {
            performQuerySampleNumPassed(renderingFunc);
            return;
        }
        
        performQuery(renderingFunc, GL33.GL_ANY_SAMPLES_PASSED);
    }
    
    public void performQuerySampleNumPassed(Runnable renderingFunc) {
        performQuery(renderingFunc, GL15.GL_SAMPLES_PASSED);
    }
    
    private void performQuery(Runnable renderingFunc, int glQueryType) {
        Validate.isTrue(isValid());
        
        Validate.isTrue(!isQuerying);
        
        GL15.glBeginQuery(glQueryType, idQueryObject);
        
        isQuerying = true;
        
        renderingFunc.run();
        
        GL15.glEndQuery(glQueryType);
        
        isQuerying = false;
        
        hasResult = true;
    }
    
    public boolean fetchQueryResult() {
        Validate.isTrue(isValid());
        Validate.isTrue(hasResult);
        
        int result = GL15.glGetQueryObjecti(idQueryObject, GL15.GL_QUERY_RESULT);
        
        return result != 0;
    }
    
    /**
     * Immersive Aeronautics: the result if the GPU has it, without waiting: 1 a sample passed, 0 none, -1 not ready
     * (IPPortalVisibility).
     */
    public int tryFetchQueryResult() {
        if (!isValid() || !hasResult) {
            return -1;
        }
        if (GL15.glGetQueryObjecti(idQueryObject, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
            return -1;
        }
        return GL15.glGetQueryObjecti(idQueryObject, GL15.GL_QUERY_RESULT) != 0 ? 1 : 0;
    }
    
    private void dispose() {
        if (idQueryObject != -1) {
            GL15.glDeleteQueries(idQueryObject);
            idQueryObject = -1;
        }
    }
    
    public boolean isValid() {
        return idQueryObject != -1;
    }
    
    private void reset() {
        hasResult = false;
    }
    
    private static final ArrayList<GlQueryObject> queryObjects = new ArrayList<>();
    
    private static void prepareQueryObjects() {
        int[] buf = new int[500];
        GL15.glGenQueries(buf);
        for (int id : buf) {
            queryObjects.add(new GlQueryObject(id));
        }
    }
    
    public static GlQueryObject acquireQueryObject() {
        if (queryObjects.isEmpty()) {
            prepareQueryObjects();
        }
        
        return queryObjects.remove(queryObjects.size() - 1);
    }
    
    public static void returnQueryObject(GlQueryObject obj) {
        obj.reset();
        if (queryObjects.size() > 1500) {
            obj.dispose();
        }
        else {
            queryObjects.add(obj);
        }
    }
}
