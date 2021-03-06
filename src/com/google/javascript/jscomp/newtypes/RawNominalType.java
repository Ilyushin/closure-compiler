/*
 * Copyright 2013 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp.newtypes;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.javascript.rhino.Node;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Represents a class or interface as defined in the code.
 * If the raw nominal type has a @template, then many nominal types can be
 * created from it by instantiation.
 *
 * @author blickly@google.com (Ben Lickly)
 * @author dimvar@google.com (Dimitris Vardoulakis)
 */
public final class RawNominalType extends Namespace {
  // The function node (if any) that defines the type
  private final Node defSite;
  // If true, we can't add more properties to this type.
  private boolean isFinalized;
  // Each instance of the class has these properties by default
  private PersistentMap<String, Property> classProps = PersistentMap.create();
  // The object pointed to by the prototype property of the constructor of
  // this class has these properties
  private PersistentMap<String, Property> protoProps = PersistentMap.create();
  // For @unrestricted, we are less strict about inexistent-prop warnings than
  // for @struct. We use this map to remember the names of props added outside
  // the constructor and the prototype methods.
  private PersistentMap<String, Property> randomProps = PersistentMap.create();
  // Consider a generic type A<T> which inherits from a generic type B<T>.
  // All instantiated A classes, such as A<number>, A<string>, etc,
  // have the same superclass and interfaces fields, because they have the
  // same raw type. You need to instantiate these fields to get the correct
  // type maps, eg, see NominalType#isSubtypeOf.
  private NominalType superClass = null;
  private ImmutableSet<NominalType> interfaces = null;
  private final boolean isInterface;
  // Used in GlobalTypeInfo to find type mismatches in the inheritance chain.
  private ImmutableSet<String> allProps = null;
  // In GlobalTypeInfo, we request (wrapped) RawNominalTypes in various
  // places. Create them here and cache them to save mem.
  private final NominalType wrappedAsNominal;
  private final JSType wrappedAsJSType;
  private final JSType wrappedAsNullableJSType;
  // Empty iff this type is not generic
  private final ImmutableList<String> typeParameters;
  private final ObjectKind objectKind;
  private FunctionType ctorFn;
  private JSTypes commonTypes;

  private RawNominalType(
      Node defSite, String name, ImmutableList<String> typeParameters,
      boolean isInterface, ObjectKind objectKind) {
    Preconditions.checkNotNull(objectKind);
    Preconditions.checkState(defSite == null || defSite.isFunction());
    if (typeParameters == null) {
      typeParameters = ImmutableList.of();
    }
    this.name = name;
    this.defSite = defSite;
    this.typeParameters = typeParameters;
    this.isInterface = isInterface;
    this.objectKind = objectKind;
    this.wrappedAsNominal = new NominalType(ImmutableMap.<String, JSType>of(), this);
    ObjectType objInstance;
    switch (name) {
      case "Function":
        objInstance = ObjectType.fromFunction(FunctionType.TOP_FUNCTION, this.wrappedAsNominal);
        break;
      case "Object":
        // We do this to avoid having two instances of ObjectType that both
        // represent the top JS object.
        objInstance = ObjectType.TOP_OBJECT;
        break;
      default:
        objInstance = ObjectType.fromNominalType(this.wrappedAsNominal);
    }
    this.wrappedAsJSType = JSType.fromObjectType(objInstance);
    this.wrappedAsNullableJSType = JSType.join(JSType.NULL, this.wrappedAsJSType);
  }

  public static RawNominalType makeUnrestrictedClass(
      Node defSite, QualifiedName name, ImmutableList<String> typeParameters) {
    return new RawNominalType(
        defSite, name.toString(), typeParameters, false, ObjectKind.UNRESTRICTED);
  }

  public static RawNominalType makeStructClass(
      Node defSite, QualifiedName name, ImmutableList<String> typeParameters) {
    return new RawNominalType(
        defSite, name.toString(), typeParameters, false, ObjectKind.STRUCT);
  }

  public static RawNominalType makeDictClass(
      Node defSite, QualifiedName name, ImmutableList<String> typeParameters) {
    return new RawNominalType(
        defSite, name.toString(), typeParameters, false, ObjectKind.DICT);
  }

  public static RawNominalType makeInterface(
      Node defSite, QualifiedName name, ImmutableList<String> typeParameters) {
    // interfaces are struct by default
    return new RawNominalType(
        defSite, name.toString(), typeParameters, true, ObjectKind.STRUCT);
  }

  public Node getDefSite() {
    return this.defSite;
  }

  public boolean isClass() {
    return !isInterface;
  }

  public boolean isInterface() {
    return isInterface;
  }

  boolean isGeneric() {
    return !typeParameters.isEmpty();
  }

  public boolean isStruct() {
    return objectKind.isStruct();
  }

  public boolean isDict() {
    return objectKind.isDict();
  }

  public boolean isFinalized() {
    return this.isFinalized;
  }

  ImmutableList<String> getTypeParameters() {
    return typeParameters;
  }

  ObjectKind getObjectKind() {
    return this.objectKind;
  }

  public void setCtorFunction(
      FunctionType ctorFn, JSTypes commonTypes) {
    Preconditions.checkState(!this.isFinalized);
    this.ctorFn = ctorFn;
    this.commonTypes = commonTypes;
  }

  boolean hasAncestorClass(RawNominalType ancestor) {
    Preconditions.checkState(ancestor.isClass());
    if (this == ancestor) {
      return true;
    } else if (this.superClass == null) {
      return false;
    } else {
      return this.superClass.hasAncestorClass(ancestor);
    }
  }

  /** @return Whether the superclass can be added without creating a cycle. */
  public boolean addSuperClass(NominalType superClass) {
    Preconditions.checkState(!this.isFinalized);
    Preconditions.checkState(this.superClass == null);
    if (superClass.hasAncestorClass(this)) {
      return false;
    }
    this.superClass = superClass;
    return true;
  }

  boolean hasAncestorInterface(RawNominalType ancestor) {
    Preconditions.checkState(ancestor.isInterface);
    if (this == ancestor) {
      return true;
    } else if (this.interfaces == null) {
      return false;
    } else {
      for (NominalType superInter : interfaces) {
        if (superInter.hasAncestorInterface(ancestor)) {
          return true;
        }
      }
      return false;
    }
  }

  /** @return Whether the interface can be added without creating a cycle. */
  public boolean addInterfaces(ImmutableSet<NominalType> interfaces) {
    Preconditions.checkState(!this.isFinalized);
    Preconditions.checkState(this.interfaces == null);
    Preconditions.checkNotNull(interfaces);
    if (this.isInterface) {
      for (NominalType interf : interfaces) {
        if (interf.hasAncestorInterface(this)) {
          this.interfaces = ImmutableSet.of();
          return false;
        }
      }
    }
    this.interfaces = interfaces;
    return true;
  }

  public NominalType getSuperClass() {
    return superClass;
  }

  public ImmutableSet<NominalType> getInterfaces() {
    return this.interfaces == null ? ImmutableSet.<NominalType>of() : this.interfaces;
  }

  private Property getOwnProp(String pname) {
    Property p = classProps.get(pname);
    if (p != null) {
      return p;
    }
    p = randomProps.get(pname);
    if (p != null) {
      return p;
    }
    return protoProps.get(pname);
  }

  private Property getPropFromClass(String pname) {
    Preconditions.checkState(!isInterface);
    Property p = getOwnProp(pname);
    if (p != null) {
      return p;
    }
    if (superClass != null) {
      p = superClass.getProp(pname);
      if (p != null) {
        return p;
      }
    }
    return null;
  }

  private Property getPropFromInterface(String pname) {
    Preconditions.checkState(isInterface);
    Property p = getOwnProp(pname);
    if (p != null) {
      return p;
    }
    if (interfaces != null) {
      for (NominalType interf : interfaces) {
        p = interf.getProp(pname);
        if (p != null) {
          return p;
        }
      }
    }
    return null;
  }

  Property getProp(String pname) {
    if (isInterface) {
      return getPropFromInterface(pname);
    }
    return getPropFromClass(pname);
  }

  public boolean mayHaveOwnProp(String pname) {
    return getOwnProp(pname) != null;
  }

  public boolean mayHaveProp(String pname) {
    return getProp(pname) != null;
  }

  public JSType getInstancePropDeclaredType(String pname) {
    Property p = getProp(pname);
    if (p == null) {
      return null;
    } else if (p.getDeclaredType() == null && superClass != null) {
      return superClass.getPropDeclaredType(pname);
    }
    return p.getDeclaredType();

  }

  public Set<String> getAllOwnProps() {
    Set<String> ownProps = new LinkedHashSet<>();
    ownProps.addAll(classProps.keySet());
    ownProps.addAll(protoProps.keySet());
    return ownProps;
  }

  ImmutableSet<String> getAllPropsOfInterface() {
    Preconditions.checkState(isInterface);
    Preconditions.checkState(this.isFinalized);
    if (allProps == null) {
      ImmutableSet.Builder<String> builder = ImmutableSet.builder();
      if (interfaces != null) {
        for (NominalType interf : interfaces) {
          builder.addAll(interf.getAllPropsOfInterface());
        }
      }
      allProps = builder.addAll(protoProps.keySet()).build();
    }
    return allProps;
  }

  ImmutableSet<String> getAllPropsOfClass() {
    Preconditions.checkState(!isInterface);
    Preconditions.checkState(this.isFinalized);
    if (allProps == null) {
      ImmutableSet.Builder<String> builder = ImmutableSet.builder();
      if (superClass != null) {
        builder.addAll(superClass.getAllPropsOfClass());
      }
      allProps = builder.addAll(classProps.keySet()).addAll(protoProps.keySet()).build();
    }
    return allProps;
  }

  public void addPropertyWhichMayNotBeOnAllInstances(String pname, JSType type) {
    Preconditions.checkState(!this.isFinalized);
    if (this.classProps.containsKey(pname) || this.protoProps.containsKey(pname)) {
      return;
    }
    if (this.objectKind == ObjectKind.UNRESTRICTED) {
      this.randomProps = this.randomProps.with(
          pname, Property.make(type == null ? JSType.UNKNOWN : type, type));
    }
  }

  //////////// Class Properties

  /** Add a new non-optional declared property to instances of this class */
  public void addClassProperty(String pname, Node defSite, JSType type, boolean isConstant) {
    Preconditions.checkState(!this.isFinalized);
    if (type == null && isConstant) {
      type = JSType.UNKNOWN;
    }
    this.classProps = this.classProps.with(pname, isConstant
        ? Property.makeConstant(defSite, type, type)
        : Property.makeWithDefsite(defSite, type, type));
    // Upgrade any proto props to declared, if present
    if (this.protoProps.containsKey(pname)) {
      addProtoProperty(pname, defSite, type, isConstant);
    }
    if (this.randomProps.containsKey(pname)) {
      this.randomProps = this.randomProps.without(pname);
    }
  }

  /** Add a new undeclared property to instances of this class */
  public void addUndeclaredClassProperty(String pname, Node defSite) {
    Preconditions.checkState(!this.isFinalized);
    // Only do so if there isn't a declared prop already.
    if (mayHaveProp(pname)) {
      return;
    }
    classProps = classProps.with(pname, Property.makeWithDefsite(defSite, JSType.UNKNOWN, null));
  }

  //////////// Prototype Properties

  /** Add a new non-optional declared prototype property to this class */
  public void addProtoProperty(String pname, Node defSite, JSType type, boolean isConstant) {
    Preconditions.checkState(!this.isFinalized);
    if (type == null && isConstant) {
      type = JSType.UNKNOWN;
    }
    if (this.classProps.containsKey(pname)
        && this.classProps.get(pname).getDeclaredType() == null) {
      this.classProps = this.classProps.without(pname);
    }
    if (this.randomProps.containsKey(pname)) {
      this.randomProps = this.randomProps.without(pname);
    }
    this.protoProps = this.protoProps.with(pname, isConstant
        ? Property.makeConstant(defSite, type, type)
        : Property.makeWithDefsite(defSite, type, type));
  }

  /** Add a new undeclared prototype property to this class */
  public void addUndeclaredProtoProperty(String pname, Node defSite) {
    Preconditions.checkState(!this.isFinalized);
    if (!this.protoProps.containsKey(pname)
        || this.protoProps.get(pname).getDeclaredType() == null) {
      this.protoProps = this.protoProps.with(pname,
          Property.makeWithDefsite(defSite, JSType.UNKNOWN, null));
      if (this.randomProps.containsKey(pname)) {
        this.randomProps = this.randomProps.without(pname);
      }
    }
  }

  // Returns the object referred to by the prototype property of the
  // constructor of this class.
  private JSType createProtoObject() {
    return JSType.fromObjectType(ObjectType.makeObjectType(
        this.superClass, this.protoProps, null, false, ObjectKind.UNRESTRICTED));
  }

  //////////// Constructor Properties

  public boolean hasCtorProp(String pname) {
    return super.hasProp(pname);
  }

  /** Add a new non-optional declared property to this class's constructor */
  public void addCtorProperty(String pname, Node defSite, JSType type, boolean isConstant) {
    Preconditions.checkState(!this.isFinalized);
    super.addProperty(pname, defSite, type, isConstant);
  }

  /** Add a new undeclared property to this class's constructor */
  public void addUndeclaredCtorProperty(String pname, Node defSite) {
    Preconditions.checkState(!this.isFinalized);
    super.addUndeclaredProperty(pname, defSite, JSType.UNKNOWN, false);
  }

  public JSType getCtorPropDeclaredType(String pname) {
    return super.getPropDeclaredType(pname);
  }

  // Returns the (function) object referred to by the constructor of this class.
  // TODO(dimvar): this function shouldn't take any arguments; it should
  // construct and cache the result based on the fields.
  // But currently a couple of unit tests break because of "structural"
  // constructors with a different number of arguments.
  // For those, we should just be creating a basic function type, not be
  // adding all the static properties.
  JSType getConstructorObject(FunctionType ctorFn) {
    Preconditions.checkState(this.isFinalized);
    if (this.ctorFn != ctorFn || this.namespaceType == null) {
      ObjectType ctorFnAsObj = ObjectType.makeObjectType(
          this.commonTypes.getFunctionType(), this.otherProps, ctorFn,
          ctorFn.isLoose(), ObjectKind.UNRESTRICTED);
      return withNamedTypes(this.commonTypes, ctorFnAsObj);
    }
    return this.namespaceType;
  }

  public void finalize() {
    Preconditions.checkState(!this.isFinalized);
    Preconditions.checkNotNull(this.ctorFn);
    if (this.interfaces == null) {
      this.interfaces = ImmutableSet.of();
    }
    addCtorProperty("prototype", null, createProtoObject(), false);
    this.isFinalized = true;
  }

  StringBuilder appendTo(StringBuilder builder) {
    builder.append(name);
    if (!this.typeParameters.isEmpty()) {
      builder.append("<" + Joiner.on(",").join(this.typeParameters) + ">");
    }
    return builder;
  }

  @Override
  public String toString() {
    return appendTo(new StringBuilder()).toString();
  }

  @Override
  protected JSType computeJSType(JSTypes commonTypes) {
    return getConstructorObject(this.ctorFn);
  }

  public NominalType getAsNominalType() {
    return this.wrappedAsNominal;
  }

  // Don't confuse with the toJSType method, inherited from Namespace.
  // The namespace is represented by the constructor, so that method wraps the
  // constructor in a JSType, and this method wraps the instance.
  public JSType getInstanceAsJSType() {
    return wrappedAsJSType;
  }

  public JSType getInstanceWithNullability(boolean includeNull) {
    return includeNull ? wrappedAsNullableJSType : wrappedAsJSType;
  }

  // equals and hashCode default to reference equality, which is what we want
}
