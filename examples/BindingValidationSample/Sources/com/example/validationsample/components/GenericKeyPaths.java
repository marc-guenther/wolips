package com.example.validationsample.components;

import com.webobjects.appserver.WOContext;

import com.example.validationsample.model.AddressRepository;
import com.example.validationsample.model.PersonRepository;

public class GenericKeyPaths extends BaseComponent {
	public GenericKeyPaths(WOContext context) { super(context); }

	public PersonRepository personRepository() { return null; }
	public AddressRepository addressRepository() { return null; }
}
