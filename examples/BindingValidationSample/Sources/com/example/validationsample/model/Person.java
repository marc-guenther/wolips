package com.example.validationsample.model;

import com.webobjects.appserver.WOComponent;
import com.webobjects.foundation.NSArray;

/**
 * Plain (non-KVC) model object so that unknown keys produce hard
 * "there is no key" errors rather than KVC "unable to verify" warnings.
 * Members are shaped to exercise the various binding cases.
 */
public class Person {
	public String name() { return null; }            // simple String getter
	public int age() { return 0; }                   // primitive leaf
	public boolean isActive() { return false; }      // boolean -> "active" binding via "is" prefix
	public Address address() { return null; }        // navigable concrete type
	public Person bestFriend() { return null; }      // self-referential navigation
	public NSArray<Person> friends() { return null; }// collection passthrough + @operators

	public WOComponent relatedComponent() { return null; } // navigation into a WOComponent

	public String nickname() { return null; }        // gettable AND settable
	public void setNickname(String nickname) { }

	public String computedValue() { return null; }   // gettable, NOT settable

	@Deprecated
	public String oldName() { return null; }         // binding to a deprecated member
}
