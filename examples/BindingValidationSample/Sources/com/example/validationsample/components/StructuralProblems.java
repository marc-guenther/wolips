package com.example.validationsample.components;

import com.webobjects.appserver.WOContext;

import com.example.validationsample.model.Person;

public class StructuralProblems extends BaseComponent {
	public StructuralProblems(WOContext context) { super(context); }

	public Person person() { return null; }
}
