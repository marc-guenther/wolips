package com.example.validationsample.components;

import com.webobjects.appserver.WOContext;

import com.example.validationsample.model.Person;

public class OgnlBindings extends BaseComponent {
	public OgnlBindings(WOContext context) { super(context); }

	public Person person() { return null; }
}
