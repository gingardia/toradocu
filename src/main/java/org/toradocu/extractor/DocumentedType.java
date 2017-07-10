package org.toradocu.extractor;

import java.util.List;
import java.util.Objects;
import org.toradocu.util.Checks;

/** Represents a class or interface that is documented with Javadoc comments. */
public final class DocumentedType {

  /** Documented class or interface (or enum, ...). */
  private final Class<?> documentedType;
  /** Constructors and methods of this documented type. */
  private final List<DocumentedExecutable> documentedExecutables;

  /**
   * Creates a new DocumentedType wrapping the given class and with the given constructors and
   * methods.
   *
   * @param documentedClass the {@code Class} of this documentedClass
   * @param documentedExecutables constructors and methods of {@code documentedClass}
   * @throws NullPointerException if either documentedClass or documentedExecutables is null
   */
  DocumentedType(Class<?> documentedClass, List<DocumentedExecutable> documentedExecutables) {
    Checks.nonNullParameter(documentedClass, "documentedClass");
    Checks.nonNullParameter(documentedExecutables, "documentedExecutables");
    this.documentedType = documentedClass;
    this.documentedExecutables = documentedExecutables;
  }

  /**
   * Returns the runtime class of the documented type this DocumentedType represents.
   *
   * @return the runtime class of the documented type this DocumentedType represents
   */
  Class<?> getDocumentedClass() {
    return documentedType;
  }

  /**
   * Returns constructors and methods of this {@code documentedType}.
   *
   * @return constructors and methods of this {@code documentedType}
   */
  public List<DocumentedExecutable> getDocumentedExecutables() {
    return documentedExecutables;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DocumentedType)) {
      return false;
    }
    DocumentedType that = (DocumentedType) o;
    return documentedType.equals(that.documentedType)
        && documentedExecutables.equals(that.documentedExecutables);
  }

  @Override
  public int hashCode() {
    return Objects.hash(documentedType, documentedExecutables);
  }
}
