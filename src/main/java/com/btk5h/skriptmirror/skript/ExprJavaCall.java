package com.btk5h.skriptmirror.skript;

import com.btk5h.skriptmirror.Descriptor;
import com.btk5h.skriptmirror.JavaType;
import com.btk5h.skriptmirror.LRUCache;
import com.btk5h.skriptmirror.Null;
import com.btk5h.skriptmirror.Util;

import org.bukkit.event.Event;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ch.njol.skript.Skript;
import ch.njol.skript.SkriptAPIException;
import ch.njol.skript.classes.Changer;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.lang.UnparsedLiteral;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.registrations.Converters;
import ch.njol.skript.util.Utils;
import ch.njol.util.Checker;
import ch.njol.util.Kleenean;
import ch.njol.util.coll.iterator.ArrayIterator;

public class ExprJavaCall<T> implements Expression<T> {
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();
  private static final Object[] NO_ARGS = new Object[0];
  private static final Descriptor CONSTRUCTOR_DESCRIPTOR = Descriptor.create("<init>");

  static {
    //noinspection unchecked
    Skript.registerExpression(ExprJavaCall.class, Object.class,
        ExpressionType.PATTERN_MATCHES_EVERYTHING,
        "%object%..%string%(0¦!|1¦\\([%-objects%]\\))",
        "%object%.<[\\w$.\\[\\]]+>(0¦!|1¦\\([%-objects%]\\))",
        "new %javatype%\\([%-objects%]\\)");
  }

  private enum Type {
    FIELD, METHOD, CONSTRUCTOR;

    @Override
    public String toString() {
      return name().toLowerCase();
    }
  }

  private LRUCache<Descriptor, Collection<MethodHandle>> callSiteCache = new LRUCache<>(8);

  private Expression<Object> targetArg;
  private Expression<Object> args;

  private Type type;
  private boolean isDynamic;
  private boolean suppressErrors = false;

  private Descriptor staticDescriptor;
  private Expression<String> dynamicDescriptor;

  private final ExprJavaCall<?> source;
  private final Class<? extends T>[] types;
  private final Class<T> superType;

  @SuppressWarnings("unchecked")
  public ExprJavaCall() {
    this(null, (Class<? extends T>) Object.class);
  }

  @SuppressWarnings("unchecked")
  @SafeVarargs
  private ExprJavaCall(ExprJavaCall<?> source, Class<? extends T>... types) {
    this.source = source;

    if (source != null) {
      this.targetArg = source.targetArg;
      this.args = source.args;
      this.type = source.type;
      this.suppressErrors = source.suppressErrors;
      this.staticDescriptor = source.staticDescriptor;
      this.dynamicDescriptor = source.dynamicDescriptor;
    }

    this.types = types;
    this.superType = (Class<T>) Utils.getSuperType(types);
  }

  private Collection<MethodHandle> getCallSite(Descriptor e) {
    return callSiteCache.computeIfAbsent(e, this::createCallSite);
  }

  private Collection<MethodHandle> createCallSite(Descriptor e) {
    Class<?> javaClass = e.getJavaClass();

    switch (type) {
      case FIELD:
        return Util.fields(javaClass)
            .filter(f -> f.getName().equals(e.getIdentifier()))
            .peek(f -> f.setAccessible(true))
            .flatMap(Util.propagateErrors(f -> Stream.of(
                LOOKUP.unreflectGetter(f),
                LOOKUP.unreflectSetter(f)
            )))
            .filter(Objects::nonNull)
            .limit(2)
            .collect(Collectors.toList());
      case METHOD:
        return Util.methods(javaClass)
            .filter(m -> m.getName().equals(e.getIdentifier()))
            .peek(m -> m.setAccessible(true))
            .map(Util.propagateErrors(LOOKUP::unreflect))
            .filter(Objects::nonNull)
            //.map(ExprJavaCall::asSpreader)
            .collect(Collectors.toList());
      case CONSTRUCTOR:
        return Util.constructor(javaClass)
            .peek(c -> c.setAccessible(true))
            .map(Util.propagateErrors(LOOKUP::unreflectConstructor))
            .filter(Objects::nonNull)
            //.map(ExprJavaCall::asSpreader)
            .collect(Collectors.toList());
      default:
        throw new IllegalStateException();
    }
  }

  private static MethodHandle asSpreader(MethodHandle mh) {
    int paramCount = mh.type().parameterCount();
    if (mh.isVarargsCollector()) {
      if (paramCount == 1) {
        return mh;
      }

      return mh.asSpreader(Object[].class, paramCount - 1);
    }

    return mh.asSpreader(Object[].class, paramCount);
  }

  @SuppressWarnings("unchecked")
  private T[] invoke(Object target, Object[] arguments, Descriptor baseDescriptor) {
    T returnedValue = null;

    Class<?> targetClass = Util.toClass(target);
    Descriptor descriptor = specifyDescriptor(baseDescriptor, targetClass);

    if (descriptor.getJavaClass().isAssignableFrom(targetClass)) {
      Object[] arr;
      if (target instanceof JavaType) {
        arr = new Object[arguments.length];
        System.arraycopy(arguments, 0, arr, 0, arguments.length);
      } else {
        arr = new Object[arguments.length + 1];
        arr[0] = target;
        System.arraycopy(arguments, 0, arr, 1, arguments.length);
      }

      Optional<MethodHandle> method = selectMethod(descriptor, arr);

      if (method.isPresent()) {
        MethodHandle mh = method.get();

        convertTypes(mh.type(), arr);

        try {
          returnedValue = (T) mh.invokeWithArguments(arr);
        } catch (Throwable throwable) {
          if (!suppressErrors) {
            Skript.warning(
                String.format("%s %s%s threw a %s: %s%n" +
                        "Run Skript with the verbosity 'very high' for the stack trace.",
                    type, descriptor, optionalArgs(arguments), throwable.getClass().getSimpleName(),
                    throwable.getMessage()));

            if (Skript.logVeryHigh()) {
              StringWriter errors = new StringWriter();
              throwable.printStackTrace(new PrintWriter(errors));
              Skript.warning(errors.toString());
            }
          }
        }
      } else {
        if (!suppressErrors) {
          Skript.warning(
              String.format("No matching %s: %s%s",
                  type, descriptor, optionalArgs(arguments)));
        }
      }
    } else {
      if (!suppressErrors) {
        Skript.warning(String.format("Incompatible %s call: %s on %s",
            type, descriptor, Util.getDebugName(targetClass)));
      }
    }

    if (returnedValue == null) {
      return Util.newArray(superType, 0);
    }

    T converted = Converters.convert(returnedValue, types);

    if (converted == null) {
      if (!suppressErrors) {
        String toClasses = Arrays.stream(types)
            .map(Util::getDebugName)
            .collect(Collectors.joining(", "));
        Skript.warning(
            String.format("%s %s%s returned %s, which could not be converted to %s",
                type, descriptor, optionalArgs(arguments), toString(returnedValue), toClasses));
      }
      return Util.newArray(superType, 0);
    }

    T[] returnArray = Util.newArray(superType, 1);
    returnArray[0] = converted;
    return returnArray;
  }

  private String optionalArgs(Object... arguments) {
    if (arguments.length == 0) {
      return "";
    }

    return " called with (" + toString(arguments) + ")";
  }

  private String toString(Object... arguments) {
    return Arrays.stream(arguments)
        .map(arg -> String.format("%s (%s)",
            Classes.toString(arg), Util.getDebugName(arg.getClass())))
        .collect(Collectors.joining(", "));
  }

  @SuppressWarnings("ThrowableNotThrown")
  private Descriptor getDescriptor(Event e) {
    if (isDynamic) {
      String desc = dynamicDescriptor.getSingle(e);

      if (desc == null) {
        return null;
      }

      try {
        return Descriptor.parse(desc);
      } catch (ClassNotFoundException ex) {
        if (!suppressErrors) {
          Skript.exception(ex);
        }
        return Descriptor.create(null);
      }
    }

    return staticDescriptor;
  }

  private static Descriptor specifyDescriptor(Descriptor descriptor, Class<?> cls) {
    if (descriptor.getJavaClass() != null) {
      return descriptor;
    }

    return Descriptor.create(cls, descriptor.getIdentifier());
  }

  private Optional<MethodHandle> selectMethod(Descriptor descriptor, Object[] args) {
    return getCallSite(descriptor).stream()
        .filter(mh -> matchesArgs(args, mh))
        .findFirst();
  }

  private static boolean matchesArgs(Object[] args, MethodHandle mh) {
    MethodType mt = mh.type();
    if (mt.parameterCount() != args.length && !mh.isVarargsCollector()) {
      return false;
    }

    Class<?>[] params = mt.parameterArray();

    for (int i = 0; i < params.length; i++) {
      if (i == params.length - 1 && mh.isVarargsCollector()) {
        break;
      }

      Class<?> param = params[i];
      Object arg = args[i];

      if (!param.isInstance(arg)) {
        if (arg instanceof Number && Util.NUMERIC_CLASSES.contains(param)) {
          continue;
        }

        if (param.isPrimitive() && Util.WRAPPER_CLASSES.get(param).isInstance(arg)) {
          continue;
        }

        if (arg instanceof String
            && (param == char.class || param == Character.class)
            && ((String) arg).length() == 1) {
          continue;
        }

        if (param == Class.class && arg instanceof JavaType) {
          continue;
        }

        if (!param.isPrimitive() && arg instanceof Null) {
          continue;
        }

        return false;
      }
    }

    return true;
  }

  private static void convertTypes(MethodType mt, Object[] args) {
    if (!mt.hasPrimitives()) {
      return;
    }

    Class<?>[] params = mt.parameterArray();

    for (int i = 0; i < params.length; i++) {
      Class<?> param = params[i];

      if (param.isPrimitive() && args[i] instanceof Number) {
        if (param == byte.class) {
          args[i] = ((Number) args[i]).byteValue();
        } else if (param == double.class) {
          args[i] = ((Number) args[i]).doubleValue();
        } else if (param == float.class) {
          args[i] = ((Number) args[i]).floatValue();
        } else if (param == int.class) {
          args[i] = ((Number) args[i]).intValue();
        } else if (param == long.class) {
          args[i] = ((Number) args[i]).longValue();
        } else if (param == short.class) {
          args[i] = ((Number) args[i]).shortValue();
        }
      }

      if (args[i] instanceof String
          && (param == char.class || param == Character.class)) {
        args[i] = ((String) args[i]).charAt(0);
      }

      if (param == Class.class && args[i] instanceof JavaType) {
        args[i] = ((JavaType) args[i]).getJavaClass();
      }

      if (args[i] instanceof Null) {
        args[i] = null;
      }
    }
  }

  void setSuppressErrors(boolean suppressErrors) {
    this.suppressErrors = suppressErrors;

    if (targetArg instanceof ExprJavaCall) {
      ((ExprJavaCall) targetArg).setSuppressErrors(suppressErrors);
    }
  }

  @Override
  public T getSingle(Event e) {
    T[] all = getArray(e);
    if (all == null || all.length == 0) {
      return null;
    }
    return all[0];
  }

  @Override
  public T[] getArray(Event e) {
    return getAll(e);
  }

  @SuppressWarnings("unchecked")
  @Override
  public T[] getAll(Event e) {
    Object target = targetArg.getSingle(e);
    Object[] arguments;

    if (target == null) {
      return null;
    }

    if (args != null) {
      try {
        arguments = args.getArray(e);
      } catch (SkriptAPIException ex) {
        Skript.error("The arguments passed to " + getDescriptor(e) + " could not be parsed. Try " +
            "setting a list variable to the arguments and pass that variable to the reflection " +
            "call instead!");
        return null;
      }
    } else {
      arguments = NO_ARGS;
    }

    return invoke(target, arguments, getDescriptor(e));
  }

  @Override
  public boolean isSingle() {
    return true;
  }

  @Override
  public boolean check(Event e, Checker<? super T> c, boolean negated) {
    return SimpleExpression.check(getAll(e), c, negated, getAnd());
  }

  @Override
  public boolean check(Event e, Checker<? super T> c) {
    return SimpleExpression.check(getAll(e), c, false, getAnd());
  }

  @Override
  public <R> Expression<? extends R> getConvertedExpression(Class<R>[] to) {
    return new ExprJavaCall<>(this, to);
  }

  @Override
  public Class<T> getReturnType() {
    return superType;
  }

  @Override
  public boolean getAnd() {
    return true;
  }

  @Override
  public boolean setTime(int time) {
    return false;
  }

  @Override
  public int getTime() {
    return 0;
  }

  @Override
  public boolean isDefault() {
    return false;
  }

  @Override
  public Iterator<? extends T> iterator(Event e) {
    return new ArrayIterator<>(getAll(e));
  }

  @Override
  public boolean isLoopOf(String s) {
    return false;
  }

  @Override
  public Expression<?> getSource() {
    return source == null ? this : source;
  }

  @Override
  public Expression<? extends T> simplify() {
    return this;
  }

  @Override
  public String toString(Event e, boolean debug) {
    Descriptor descriptor = getDescriptor(e);

    if (descriptor == null) {
      return "java call";
    }

    return descriptor.toString();
  }

  @Override
  public Class<?>[] acceptChange(Changer.ChangeMode mode) {
    if (type == Type.FIELD &&
        (mode == Changer.ChangeMode.SET || mode == Changer.ChangeMode.DELETE)) {
      return new Class<?>[]{Object.class};
    }
    return null;
  }

  @Override
  public void change(Event e, Object[] delta, Changer.ChangeMode mode) {
    Object target = targetArg.getSingle(e);
    if (target == null) {
      return;
    }

    Object[] args = new Object[1];

    switch (mode) {
      case SET:
        args[0] = delta[0];
        break;
      case DELETE:
        args[0] = null;
        break;
    }

    invoke(target, args, getDescriptor(e));
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed,
                      SkriptParser.ParseResult parseResult) {
    targetArg = (Expression<Object>) exprs[0];
    args = (Expression<Object>) exprs[matchedPattern == 0 ? 2 : 1];

    if (targetArg instanceof UnparsedLiteral || args instanceof UnparsedLiteral) {
      return false;
    }

    switch (matchedPattern) {
      case 0:
        isDynamic = true;
        type = parseResult.mark == 0 ? Type.FIELD : Type.METHOD;

        dynamicDescriptor = (Expression<String>) exprs[1];
        break;
      case 1:
        isDynamic = false;
        type = parseResult.mark == 0 ? Type.FIELD : Type.METHOD;
        String desc = parseResult.regexes.get(0).group();

        try {
          staticDescriptor = Descriptor.parse(desc);

          if (staticDescriptor == null) {
            Skript.error(desc + " is not a valid descriptor.");
            return false;
          }

          if (staticDescriptor.getJavaClass() != null
              && getCallSite(staticDescriptor).size() == 0) {
            Skript.error(desc + " refers to a non-existent method/field.");
            return false;
          }
        } catch (ClassNotFoundException e) {
          Skript.error(desc + " refers to a non-existent class.");
          return false;
        }
        break;
      case 2:
        type = Type.CONSTRUCTOR;
        staticDescriptor = CONSTRUCTOR_DESCRIPTOR;
        break;
    }

    return true;
  }
}
