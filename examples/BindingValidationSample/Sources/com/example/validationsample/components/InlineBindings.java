package com.example.validationsample.components;

import com.webobjects.appserver.WOContext;

import com.example.validationsample.model.Person;

public class InlineBindings extends BaseComponent {
	public InlineBindings(WOContext context) { super(context); }

	public Person person() { return null; }
}
