package org.toradocu.extractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.toradocu.conf.Configuration;
import org.toradocu.doclet.formats.html.ConfigurationImpl;
import org.toradocu.doclet.formats.html.HtmlDocletWriter;
import org.toradocu.doclet.internal.toolkit.taglets.TagletWriter;
import org.toradocu.doclet.internal.toolkit.util.DocFinder;
import org.toradocu.doclet.internal.toolkit.util.DocPath;
import org.toradocu.doclet.internal.toolkit.util.ImplementedMethods;
import org.toradocu.util.OutputPrinter;

import com.sun.javadoc.AnnotationDesc;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.ConstructorDoc;
import com.sun.javadoc.Doc;
import com.sun.javadoc.ExecutableMemberDoc;
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.Tag;
import com.sun.javadoc.Type;

public final class JavadocExtractor {
	
	/** Holds Javadoc doclet configuration options. */
	private final ConfigurationImpl configuration;
	/** {@code Logger} for this class. */
	private final Logger LOG;

	/**
	 * Constructs a {@code JavadocExtractor} with the given doclet {@code configuration}.
	 * 
	 * @param configuration the Javadoc doclet configuration
	 */
	public JavadocExtractor(ConfigurationImpl configuration) {
		this.configuration = configuration;
		LOG = LoggerFactory.getLogger(JavadocExtractor.class);
	}
	
	/**
	 * Returns a list of {@code DocumentedMethod}s extracted from the given {@code classDoc}.
	 * 
	 * @param classDoc the {@code ClassDoc} from which to extract method documentation
	 * @return a list containing documented methods from the class
	 * @throws IOException if the method encounters an error while reading/generating class documentation
	 */
	public List<DocumentedMethod> extract(ClassDoc classDoc) throws IOException {
		List<DocumentedMethod> methods = new ArrayList<>();
		
		// Loop on constructors and methods (also inherited) of the target class.
		for (ExecutableMemberDoc member : getConstructorsAndMethods(classDoc)) {
			org.toradocu.extractor.Type containgClass = new org.toradocu.extractor.Type(member.containingClass().qualifiedName());
			List<Tag> throwsTags = new ArrayList<>();
			
			// Collect tags in the current method's documentation. This is needed because DocFinder.search
            // does not load tags of a method when the method overrides a superclass' method also
            // overwriting the Javadoc documentation.
			Collections.addAll(throwsTags, member.tags("@throws"));
			Collections.addAll(throwsTags, member.tags("@exception"));
    		
			// Collect tags that are automatically inherited (i.e., when there is no comment for a method
			// overriding another one).
			Doc holder = DocFinder.search(new DocFinder.Input(member)).holder;
			Collections.addAll(throwsTags, holder.tags("@throws"));
			Collections.addAll(throwsTags, holder.tags("@exception"));
    		
			// Collect tags from method definitions in interfaces. This is not done by DocFinder.search
            // (at least in the way we use it).
			if (holder instanceof MethodDoc) {
				ImplementedMethods implementedMethods = new ImplementedMethods((MethodDoc) holder, configuration);
				for (MethodDoc implementedMethod : implementedMethods.build()) {
					Collections.addAll(throwsTags, implementedMethod.tags("@throws"));
					Collections.addAll(throwsTags, implementedMethod.tags("@exception"));
				}
			}
			
			List<ThrowsTag> memberTags = new ArrayList<>();
    		for (Tag tag : throwsTags) {
    			if (!(tag instanceof com.sun.javadoc.ThrowsTag)) {
    				throw new IllegalStateException(tag + " is not a @Throws tag. This should not happen. Toradocu only considers @throws tags.");
    			}
    			
    			com.sun.javadoc.ThrowsTag throwsTag = (com.sun.javadoc.ThrowsTag) tag;
    			 // Handle inline taglets such as {@inheritDoc}.
    			TagletWriter tagletWriter = new HtmlDocletWriter(configuration, DocPath.forClass(classDoc)).getTagletWriterInstance(false);
    			String comment = tagletWriter.commentTagsToOutput(tag, tag.inlineTags()).toString();
    			/* Remove HTML tags (also generated by inline taglets). In the future, perhaps retain those tags, 
    			 * because they contain information that can be exploited. */
    			comment = Jsoup.parse(comment).text(); 
    			ThrowsTag tagToProcess = new ThrowsTag(new org.toradocu.extractor.Type(getExceptionName(throwsTag, member)), comment);
    			memberTags.add(tagToProcess);
    		}
    
    		methods.add(new DocumentedMethod(containgClass, member.name(), getReturnType(member), getParameters(member), member.isVarArgs(), memberTags));
		}
	
		printOutput(methods);
		return methods;
	}
	
	/**
	 * Returns all constructors and methods (including inherited ones) from the given {@code ClassDoc}.
	 * Notice that methods inherited from the class {@code java.lang.Object} are ignored and not included
	 * in the returned list. 
	 * 
	 * @param classDoc the {@code ClassDoc} from which to extract constructors and methods
	 * @return a list of {@code ExecutableMemberDoc}s representing the constructors and methods of {@code classDoc}
	 */
	private List<ExecutableMemberDoc> getConstructorsAndMethods(ClassDoc classDoc) {		
		/** Constructors of the class {@code classDoc} to be returned by this method */
		List<ExecutableMemberDoc> constructors = new ArrayList<>();
		/** Methods of the class {@code classDoc} to be returned by this method */
		Map<String, ExecutableMemberDoc> methods = new LinkedHashMap<>();
		
		// Collect non-default constructors.
		for (ConstructorDoc constructor : classDoc.constructors()) {
			// This is a workaround to strange behavior of method Doc.position(). It does not return null
			// for default constructors. It instead returns the line number of the start of the class.
			if (constructor.position() == null
				|| !constructor.position().toString().equals(classDoc.position().toString())) {
				constructors.add(constructor);
			}
		}
		
		// Collect non-synthetic methods (i.e. those methods that have not been synthesized by the compiler).
		ClassDoc currentClass = classDoc; // Used to traverse over superclasses.
		while (currentClass != null && !currentClass.qualifiedName().equals("java.lang.Object")) {
			List<ExecutableMemberDoc> currentClassMethods = new ArrayList<>(Arrays.asList(currentClass.methods()));
			// Class hierarchy is traversed from from subclass to superclass. Each visited method's signature
			// is stored so that a method is considered only once, even when it is overridden.
			for (ExecutableMemberDoc method : currentClassMethods) {
				String methodID = method.name() + method.signature();
				if (!method.isSynthetic() && !methods.containsKey(methodID)) {
					methods.put(methodID, method);
				}
			}
			currentClass = currentClass.superclass();
		} 

		List<ExecutableMemberDoc> constructorsAndMethods = constructors;
		constructorsAndMethods.addAll(methods.values());
		return constructorsAndMethods;
	}
	
	/**
	 * Returns the return type of the given {@code member}.
	 * Returns {@code null} if {@code member} is a constructor.
	 * 
	 * @param member the executable member (constructor or method) to return the return type
	 * @return the return type of the given member or null if the member is a constructor
	 */
	private org.toradocu.extractor.Type getReturnType(ExecutableMemberDoc member) {
		if (member instanceof MethodDoc) {
			MethodDoc method = (MethodDoc) member;
			Type returnType = method.returnType();
			return new org.toradocu.extractor.Type(returnType.qualifiedTypeName() + returnType.dimension());
		} else {
			return null;
		}
	}
	
	/**
	 * Returns the {@code Parameter}s of the given constructor or method.
	 * 
	 * @param member the constructor or method from which to extract parameters
	 * @return an array of parameters
	 */
	private List<Parameter> getParameters(ExecutableMemberDoc member) {
		com.sun.javadoc.Parameter[] params = member.parameters();
		Parameter[] parameters = new Parameter[params.length];
		for (int i = 0; i < parameters.length; i++) {
			// Determine nullness constraints from parameter annotations.
			Boolean nullable = null;
			for (AnnotationDesc annotation : params[i].annotations()) {
				String annotationTypeName = annotation.annotationType().name().toLowerCase();
				if (annotationTypeName.equals("nullable")) {
					nullable = true;
					break;
				} else if (annotationTypeName.equals("notnull") || annotationTypeName.equals("nonnull")) {
					nullable = false;
					break;
				}
			}
			
			Type pType = params[i].type();
			String type = pType.qualifiedTypeName() + pType.dimension();
			// Set type of last parameter to array if necessary (member takes varargs).
			if (member.isVarArgs() && i == parameters.length - 1) {
				type = pType.qualifiedTypeName() + "[]";
			}
			parameters[i] = new Parameter(new org.toradocu.extractor.Type(type), params[i].name(), i, nullable);
		}
		return Arrays.asList(parameters);
	}

	/**
	 * Prints the given list of {@code DocumentedMethod}s to the configured Javadoc extractor output file.
	 * 
	 * @param methods the methods to print
	 */
	private void printOutput(List<DocumentedMethod> methods) {
		OutputPrinter.Builder builder = new OutputPrinter.Builder("JavadocExtractor", methods);
		OutputPrinter printer = builder.file(Configuration.INSTANCE.getJavadocExtractorOutput()).logger(LOG).build();
		printer.print();
	}
	
	/**
	 * This method tries to return the qualified name of the exception in the {@code throwsTag}.
	 * If the source code of the exception is not available, then just the simple name in the Javadoc
	 * comment is returned.
	 * 
	 * @param throwsTag throws tag to extract exception name from
	 * @param member the method to which this throws tag belongs 
	 * @return the name of the exception in the throws tag (qualified, if possible)
	 */
	@SuppressWarnings("deprecation") // We use deprecated method in Javadoc API. No alternative solution is documented.
	private String getExceptionName(com.sun.javadoc.ThrowsTag throwsTag, ExecutableMemberDoc member) {
		Type exceptionType = throwsTag.exceptionType();
		if (exceptionType != null) {
			return exceptionType.qualifiedTypeName(); 
		}
		// Try to collect the exception's name from the import declarations
		String exceptionName = throwsTag.exceptionName();
		for (ClassDoc importedClass : member.containingClass().importedClasses()) {
			if (importedClass.name().equals(exceptionName)) {
				return importedClass.qualifiedName();
			}
		}	
		// If fully qualified exception's name cannot be collected from import statements, return the simple name
		return exceptionName; 
	}

}
