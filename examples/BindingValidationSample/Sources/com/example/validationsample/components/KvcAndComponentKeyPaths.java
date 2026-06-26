package com.example.validationsample.components;

import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver.WOContext;

import com.example.validationsample.model.LegacyKvcObject;
import com.example.validationsample.model.Person;

public class KvcAndComponentKeyPaths extends BaseComponent {
	public KvcAndComponentKeyPaths(WOContext context) { super(context); }

	public Person person() { return null; }
	public LegacyKvcObject legacyObject() { return null; }
	public WOComponent someComponent() { return null; }
}
