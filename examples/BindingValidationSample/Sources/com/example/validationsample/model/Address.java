package com.example.validationsample.model;

/** Plain (non-KVC) model object: unknown keys on it are hard validation errors. */
public class Address {
	public String street() { return null; }
	public String city() { return null; }
	public String zipCode() { return null; }
}
