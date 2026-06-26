package com.example.validationsample.components;

import com.webobjects.appserver.WOContext;

import com.example.validationsample.model.Person;

public class UsesApiValidated extends BaseComponent {
	public UsesApiValidated(WOContext context) { super(context); }

	public Person person() { return null; }
}
