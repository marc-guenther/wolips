package com.example.validationsample.model;

import com.webobjects.foundation.NSKeyValueCoding;

/**
 * Implements NSKeyValueCoding, so unknown keys resolve to the
 * "unable to verify ... implements NSKeyValueCoding" case rather than a hard error.
 */
public class LegacyKvcObject implements NSKeyValueCoding {
	public Object valueForKey(String key) { return null; }
	public void takeValueForKey(Object value, String key) { }

	public String title() { return null; } // a concrete key that DOES validate
}
