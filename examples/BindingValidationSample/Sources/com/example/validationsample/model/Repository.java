package com.example.validationsample.model;

import com.webobjects.foundation.NSArray;

/**
 * Generic base class. Its members return the type variable T (and NSArray&lt;T&gt;),
 * so the resolved next type depends on the concrete subclass a keypath is reached
 * through. This is the case that could not previously be cached.
 */
public abstract class Repository<T> {
	public T currentObject() { return null; }
	public NSArray<T> allObjects() { return null; }
}
