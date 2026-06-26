package com.example.validationsample.components;

import com.webobjects.appserver.WOContext;

import com.example.validationsample.model.Person;

public class InvalidKeyPaths extends BaseComponent {
	public InvalidKeyPaths(WOContext context) { super(context); }

	public Person person() { return null; }
}
