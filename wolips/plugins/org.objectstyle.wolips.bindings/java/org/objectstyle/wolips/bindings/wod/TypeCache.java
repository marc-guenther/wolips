package org.objectstyle.wolips.bindings.wod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.objectstyle.wolips.bindings.api.ApiCache;
import org.objectstyle.wolips.bindings.utils.BindingReflectionUtils;
import org.objectstyle.wolips.bindings.utils.ValidationProfiler;
import org.objectstyle.wolips.core.resources.types.LimitedLRUCache;
import org.objectstyle.wolips.core.resources.types.SuperTypeHierarchyCache;

public class TypeCache {
  private Map<IJavaProject, ApiCache> _apiCache;

  private LimitedLRUCache<IType, TypeCacheEntry> _typeCacheEntries;

  public TypeCache() {
    _typeCacheEntries = new LimitedLRUCache<IType, TypeCacheEntry>(1000);
    _apiCache = new HashMap<IJavaProject, ApiCache>();
  }

  public TypeCacheEntry getTypeCacheEntry(IType type) throws JavaModelException {
    ComponentTypeDependencies.recordType(type);
    synchronized (_typeCacheEntries) {
      TypeCacheEntry entry = _typeCacheEntries.get(type);
      if (entry == null) {
        entry = new TypeCacheEntry(type);
        _typeCacheEntries.put(type, entry);
      }
      return entry;
    }
  }

  public ApiCache getApiCache(IJavaProject javaProject) {
    ApiCache apiCache;
    if (javaProject == null) {
      apiCache = new ApiCache();
    }
    else {
      synchronized (_apiCache) {
        apiCache = _apiCache.get(javaProject);
        if (apiCache == null) {
          apiCache = new ApiCache();
          _apiCache.put(javaProject, apiCache);
        }
      }
    }
    return apiCache;

  }

  public List<BindingValueKey> getBindingValueAccessorKeys(IJavaProject javaProject, IType type, String name) throws JavaModelException {
    return getTypeCacheEntry(type).getBindingValueAccessorKeys(javaProject, name);
  }

  public List<BindingValueKey> getBindingValueMutatorKeys(IJavaProject javaProject, IType type, String name) throws JavaModelException {
    return getTypeCacheEntry(type).getBindingValueMutatorKeys(javaProject, name);
  }

  public void clearCacheForProject(IProject project) {
    if (project != null) {
      //System.out.println("TypeCache.clearCacheForProject: CLEARING " + project);
      List<IType> typesToClear = new LinkedList<IType>();
      synchronized (_typeCacheEntries) {
        for (Map.Entry<IType, TypeCacheEntry> entry : _typeCacheEntries.entrySet()) {
          IResource resource = entry.getValue().getResource();
          if (resource != null && project.equals(resource.getProject())) {
            typesToClear.add(entry.getKey());
          }
        }
        for (IType typeToClear : typesToClear) {
          clearCacheForType(typeToClear);
        }
      }
    }
  }

  public void clearCacheForResource(IResource resource) {
    if (resource != null) {
      List<IType> typesToClear = new LinkedList<IType>();
      synchronized (_typeCacheEntries) {
        for (Map.Entry<IType, TypeCacheEntry> entry : _typeCacheEntries.entrySet()) {
          if (resource.equals(entry.getValue().getResource())) {
            typesToClear.add(entry.getKey());
          }
        }
        for (IType typeToClear : typesToClear) {
          clearCacheForType(typeToClear);
        }
      }
    }
  }

  public void clearCacheForType(IType declaringType) {
    synchronized (_typeCacheEntries) {
      //System.out.println("TypeCache.clearCacheForType: clearing cache for " + declaringType.getFullyQualifiedName());
      _typeCacheEntries.remove(declaringType);
      // Cached binding-key lists aggregate inherited members, so a change to this
      // type also invalidates the cached entries of its subtypes. We clear them by
      // matching against the supertype names each entry captured when it was built,
      // so no type hierarchy has to be recomputed here.
      String changedTypeName = declaringType.getFullyQualifiedName();
      List<IType> subtypesToClear = new LinkedList<IType>();
      for (Map.Entry<IType, TypeCacheEntry> cacheEntry : _typeCacheEntries.entrySet()) {
        if (cacheEntry.getValue().inheritsFrom(changedTypeName)) {
          subtypesToClear.add(cacheEntry.getKey());
        }
      }
      for (IType subtype : subtypesToClear) {
        _typeCacheEntries.remove(subtype);
      }
    }
  }

  public IType getTypeForNameInType(String typeName, IType declaringType) throws JavaModelException {
    return getTypeCacheEntry(declaringType).getTypeForName(typeName);
  }

  public void clearCache() {
    synchronized (_typeCacheEntries) {
      _typeCacheEntries.clear();
    }
  }

  public List<IType> getSupertypesOf(IType type) throws JavaModelException {
    //System.out.println("TypeCache.getSupertypesOf: " + type.getFullyQualifiedName() + " (hits=" + SuperTypeHierarchyCache.getCacheHits() + ",misses=" + SuperTypeHierarchyCache.getCacheMisses() + ")");
    try {
      List<IType> supertypes = getTypeCacheEntry(type).getSupertypes();
      // A component depends on the declaring types of inherited members too, so
      // record the whole supertype chain it was resolved against.
      for (IType supertype : supertypes) {
        ComponentTypeDependencies.recordType(supertype);
      }
      return supertypes;
    }
    catch (JavaModelException e) {
      clearCacheForType(type);
      throw e;
    }
  }

  public List<IType> getSubtypesOfInProject(IType type, IJavaProject project) throws JavaModelException {
    try {
      List<IType> subtypes = getTypeCacheEntry(type).getSubtypesInProject(project);
      // Subclass scanning (WOApplication/WOSession/WODirectAction) means a change
      // to a subtype can affect the result, so record them as dependencies too.
      for (IType subtype : subtypes) {
        ComponentTypeDependencies.recordType(subtype);
      }
      return subtypes;
    }
    catch (JavaModelException e) {
      clearCacheForType(type);
      throw e;
    }
  }

  public class TypeCacheEntry {
    private IType _type;

    private IResource _resource;

    private Map<String, IType> _nextTypeCache;

    private Map<String, List<BindingValueKey>> _bindingValueAccessorKeys;

    private Map<String, List<BindingValueKey>> _bindingValueMutatorKeys;

    /** Fully-qualified names of this type and its supertypes, captured when the
     * supertype chain is first resolved, so targeted invalidation can tell whether
     * this entry's cached binding keys depend on a changed type. */
    private Set<String> _supertypeNames;

    public TypeCacheEntry(IType type) throws JavaModelException {
      _type = type;
      // The resource lets clearCacheForResource/clearCacheForProject find this
      // entry. getResource() (unlike getUnderlyingResource()) does no model I/O
      // and returns null for binary types, which is exactly what we want -- only
      // source types in a project need invalidating when that project changes.
      _resource = _type.getResource();
      _nextTypeCache = new HashMap<String, IType>();
      _bindingValueAccessorKeys = new HashMap<String, List<BindingValueKey>>();
      _bindingValueMutatorKeys = new HashMap<String, List<BindingValueKey>>();
    }

    public IResource getResource() {
      return _resource;
    }

    public List<BindingValueKey> getBindingValueAccessorKeys(IJavaProject javaProject, String name) throws JavaModelException {
      synchronized (_bindingValueAccessorKeys) {
        List<BindingValueKey> bindingValueAccessorKeys = _bindingValueAccessorKeys.get(name);
        ValidationProfiler.count(ValidationProfiler.ACCESSOR_KEYS);
        if (bindingValueAccessorKeys == null) {
          ValidationProfiler.count(ValidationProfiler.ACCESSOR_KEYS_MISS);
          bindingValueAccessorKeys = getBindingKeys(javaProject, name, BindingReflectionUtils.ACCESSORS_OR_VOID);
          // Cache the result of the (expensive) reflection scan for every type,
          // generic or not. The scan is context-free; only a key's next-type
          // resolution is context-dependent, and that is kept out of the cache
          // by handing out copies below (see copyOf / BindingValueKey.copy).
          _bindingValueAccessorKeys.put(name, bindingValueAccessorKeys);
        }
        else {
          ValidationProfiler.count(ValidationProfiler.ACCESSOR_KEYS_HIT);
        }
        return copyOf(bindingValueAccessorKeys);
      }
    }

    public List<BindingValueKey> getBindingValueMutatorKeys(IJavaProject javaProject, String name) throws JavaModelException {
      synchronized (_bindingValueMutatorKeys) {
        List<BindingValueKey> bindingValueMutatorKeys = _bindingValueMutatorKeys.get(name);
        ValidationProfiler.count(ValidationProfiler.MUTATOR_KEYS);
        if (bindingValueMutatorKeys == null) {
          ValidationProfiler.count(ValidationProfiler.MUTATOR_KEYS_MISS);
          bindingValueMutatorKeys = getBindingKeys(javaProject, name, BindingReflectionUtils.MUTATORS_ONLY);
          // See getBindingValueAccessorKeys: cache the scan for all types and
          // hand out copies so per-keypath resolution state never leaks.
          _bindingValueMutatorKeys.put(name, bindingValueMutatorKeys);
        }
        else {
          ValidationProfiler.count(ValidationProfiler.MUTATOR_KEYS_HIT);
        }
        return copyOf(bindingValueMutatorKeys);
      }
    }

    /**
     * Returns a fresh list of copies of the cached keys. Callers (the keypath
     * walk) resolve next types against their keypath context by mutating the
     * keys they hold, so each caller must get its own copies; the cached keys
     * stay pristine and shareable across keypaths and threads.
     */
    private List<BindingValueKey> copyOf(List<BindingValueKey> bindingKeys) {
      List<BindingValueKey> copies = new LinkedList<BindingValueKey>();
      for (BindingValueKey bindingKey : bindingKeys) {
        copies.add(bindingKey.copy());
      }
      return copies;
    }

    private List<BindingValueKey> getBindingKeys(IJavaProject javaProject, String name, int accessorsOrMutators) throws JavaModelException {
      return BindingReflectionUtils.getBindingKeys(javaProject, _type, name, true, accessorsOrMutators, false, TypeCache.this)
                                   // filter out case-insensitive matches by performing an exact check
                                   .stream().filter(bvk -> bvk.getBindingName().equals(name)).toList();
    }

  	/**
  	 * Resolves a type name in the context of the declaring type.
  	 *
  	 * @param refTypeSig the type name in signature notation (for example 'QVector') this can also be an array type, but dimensions will be ignored.
  	 * @param declaringType the context for resolving (type where the reference was made in)
  	 * @return returns the fully qualified type name or build-in-type name. if a unresolved type couldn't be resolved null is returned
  	 * @throws JavaModelException thrown when the type can not be accessed
  	 */
  	public IType resolveType(String refTypeSig, IType declaringType) throws JavaModelException {
			IJavaProject javaProject = declaringType.getJavaProject();

			int arrayCount= Signature.getArrayCount(refTypeSig);
  		char type= refTypeSig.charAt(arrayCount);
  		if (type == Signature.C_UNRESOLVED) {
  			String name= ""; //$NON-NLS-1$
  			int bracket= refTypeSig.indexOf(Signature.C_GENERIC_START, arrayCount + 1);
  			if (bracket > 0)
  				name= refTypeSig.substring(arrayCount + 1, bracket);
  			else {
  				int semi= refTypeSig.indexOf(Signature.C_SEMICOLON, arrayCount + 1);
  				if (semi == -1) {
  					throw new IllegalArgumentException();
  				}
  				name= refTypeSig.substring(arrayCount + 1, semi);
  			}
  			
    		String dotTypeName = "." + name;
    		String dotBaseTypeName = null;
    		String dotExtensionTypeName = null;
    		int dotIndex = name.indexOf('.');
  			if (dotIndex != -1) {
    			// We might get a fully qualified type name -- Qcom.apple.jingle.eo.MZProgramNodeType;
  				IType resolvedType = javaProject.findType(name);
  				if (resolvedType != null) {
  					return resolvedType;
  				}
  				// If not, then this might be a nested type reference on another type, so let's split it to look for that in our imports later
  				dotBaseTypeName = "." + name.substring(0, dotIndex);
  				dotExtensionTypeName = name.substring(dotIndex);
  			}
  			
    		IImportDeclaration[] importDeclarations = declaringType.getCompilationUnit().getImports();
    		// Loop over the imports and look for the import of our symbol
    		for (IImportDeclaration declaration : importDeclarations) {
    			String importName = declaration.getElementName();
    			// If it's a .* import, then pop off the package name and lookup the type
    			if (declaration.isOnDemand()) {
    				String packageName = importName.substring(0, importName.lastIndexOf('.'));
    				String possibleTypeName = packageName + dotTypeName;
    				IType onDemandPackageType = javaProject.findType(possibleTypeName);
    				if (onDemandPackageType != null) {
    					return onDemandPackageType;
    				}
    			}
    			// If it's not a .* import, then does the import end with our type name?
    			else if (importName.endsWith(dotTypeName)) {
    				IType importType = javaProject.findType(importName);
    				if (importType != null) {
    					return importType;
    				}
    			}
    			// If it doesn't, check to see if we were a dotted type ("Outer.Inner") and check to see if Outer is imported
    			else if (dotBaseTypeName != null && importName.endsWith(dotBaseTypeName)) {
    				// ... then look for Outer.Inner
    				IType importNestedType = javaProject.findType(importName + dotExtensionTypeName);
    				if (importNestedType != null) {
    					return importNestedType;
    				}
    			}
    		}

    		// Is this a java.lang.Xxx class that we get for free?
    		String javaLangTypeName = "java.lang" + dotTypeName;
    		IType javaLangType = javaProject.findType(javaLangTypeName);
    		if (javaLangType != null) {
    			return javaLangType;
    		}
    		
    		// What about an inner type of our own class?
    		String innerTypeName = declaringType.getFullyQualifiedName('.') + dotTypeName;
    		IType innerType = javaProject.findType(innerTypeName); 
    		if (innerType != null) {
    			return innerType;
    		}

    		// Are we declared in a package?
    		IPackageFragment declaringTypePackageFragment = declaringType.getPackageFragment();
    		if (declaringTypePackageFragment != null) {
    			// ... if so, is this name in our package, so it didn't need an import?
    			String samePackageTypeName = declaringTypePackageFragment.getElementName() + dotTypeName;
    			IType samePackageType = javaProject.findType(samePackageTypeName);
    			if (samePackageType != null) {
    				return samePackageType;
    			}
    		}
    		else {
    			// If we were in the default package, is that class in the default package too?
    			IType defaultPackageType = javaProject.findType(name);
    			if (defaultPackageType != null) {
    				return defaultPackageType;
    			}
    		}
    		
    		String slowResolvedTypeName = JavaModelUtil.getResolvedTypeName(refTypeSig, _type);
    		if (slowResolvedTypeName != null) {
    			IType slowResolvedType = javaProject.findType(slowResolvedTypeName);
    			if (slowResolvedType != null) {
    				return slowResolvedType;
    			}
    		}
    		
  			return null;
  		}
  		else {
  			// We were given an Lxxx; signature ... just look it up
  			String resolvedTypeName = Signature.toString(refTypeSig.substring(arrayCount));
  			IType resolvedType = javaProject.findType(resolvedTypeName);
  			return resolvedType;
  		}
  	}

    public IType getTypeForName(String typeName) throws JavaModelException {
    	//long a = System.currentTimeMillis();
      IType type;
      if ("void".equals(typeName) || (typeName != null && typeName.length() == 1)) {
        // ignore primitives
        type = null;
      }
      else {
        ValidationProfiler.count(ValidationProfiler.GET_TYPE_FOR_NAME);
        synchronized (_nextTypeCache) {
          type = _nextTypeCache.get(typeName);
        }
        if (type != null) {
          ValidationProfiler.count(ValidationProfiler.GET_TYPE_FOR_NAME_HIT);
        }
        if (type == null) {
          ValidationProfiler.count(ValidationProfiler.GET_TYPE_FOR_NAME_MISS);
          //long t = System.currentTimeMillis();
          // MS: This call right here is the DEVIL.  This is BY FAR where the
          // majority of time is spent during component validation.  It's also
          // unfortunately completely necessary, but caching should focus on
          // this in the future.
          //String resolvedNextTypeName = JavaModelUtil.getResolvedTypeName(typeName, _type);
          long profileStart = ValidationProfiler.now();
        	type = resolveType(typeName, _type);
          ValidationProfiler.add(ValidationProfiler.RESOLVE_TYPE, profileStart);
          if (type == null) {
        	if (BindingReflectionUtils.isPrimitive(typeName)) {
        	  // ignore primitives if we get this far
        	} // We are going to hit KVCProtectedAccessor a LOT, and in most cases, it's just not going to exist, so let's save us all some trouble and skip it ...
        	else if (!"QKeyValueCodingProtectedAccessor;".equals(typeName)) {
          		//System.out.println("TypeCacheEntry.getTypeForName: Failed to resolve type name " + typeName + " in component " + _type.getElementName());
          	}
          }
          else {
            synchronized (_nextTypeCache) {
              _nextTypeCache.put(typeName, type);
            }
          }
        }
        // System.out.println("TypeCacheEntry.getTypeForName:   " + typeName + " => " + (System.currentTimeMillis() - t) + " => " + type);
      }
      //if (System.currentTimeMillis() -a > 0) {
      //	System.out.println("TypeCache.TypeCacheEntry.getTypeForName: " + type.getElementName() + " " + (System.currentTimeMillis() - a));
      //}
      return type;
    }

    public List<IType> getSupertypes() throws JavaModelException {
      ITypeHierarchy typeHierarchy = SuperTypeHierarchyCache.getTypeHierarchy(_type);
      List<IType> types = new LinkedList<IType>();
      types.add(_type);
      for (IType type : typeHierarchy.getAllSupertypes(_type)) {
        types.add(type);
      }
      if (_supertypeNames == null) {
        Set<String> supertypeNames = new HashSet<String>();
        for (IType type : types) {
          supertypeNames.add(type.getFullyQualifiedName());
        }
        _supertypeNames = supertypeNames;
      }
      return types;
    }

    /** Whether this entry's type is, or inherits from, the named type. */
    public boolean inheritsFrom(String typeName) {
      return _supertypeNames != null && _supertypeNames.contains(typeName);
    }

    public List<IType> getSubtypesInProject(IJavaProject project) throws JavaModelException {
      //System.out.println("TypeCache.getSubtypesOf: " + type.getFullyQualifiedName() + " (hits=" + SubTypeHierarchyCache.getCacheHits() + ",misses=" + SubTypeHierarchyCache.getCacheMisses() + ")");
      ITypeHierarchy typeHierarchy = _type.newTypeHierarchy(project, null);
      List<IType> types = new LinkedList<IType>();
      IType[] subtypes = typeHierarchy.getAllSubtypes(_type);
      for (int subtypeNum = subtypes.length - 1; subtypeNum >= 0; subtypeNum--) {
        types.add(subtypes[subtypeNum]);
      }
      types.add(_type);
      return types;
    }
  }
}
