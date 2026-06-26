package com.example.validationsample.components;

import com.webobjects.appserver.WOContext;

/** Marked deprecated so binding it triggers the deprecated-component warning. */
@Deprecated
public class DeprecatedComponent extends BaseComponent {
	public DeprecatedComponent(WOContext context) { super(context); }
}
