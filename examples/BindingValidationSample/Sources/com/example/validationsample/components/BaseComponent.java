package com.example.validationsample.components;

import com.webobjects.appserver.WOContext;

import er.extensions.components.ERXComponent;

/** Common superclass for the sample's components. */
public abstract class BaseComponent extends ERXComponent {
	public BaseComponent(WOContext context) {
		super(context);
	}
}
