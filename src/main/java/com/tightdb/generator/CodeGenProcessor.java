package com.tightdb.generator;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.StandardLocation;

import org.apache.commons.lang.StringUtils;

import com.tightdb.lib.Table;

@SupportedSourceVersion(SourceVersion.RELEASE_6)
public class CodeGenProcessor extends AbstractAnnotationProcessor {

	private static final String DEFAULT_PACKAGE = "com.tightdb.generated";

	public static final String INFO_GENERATED = "/* This file was automatically generated by TightDB. */";

	private static final Set<String> NUM_TYPES;

	private final CodeRenderer renderer = new CodeRenderer();

	static {
		NUM_TYPES = new HashSet<String>(Arrays.asList("long", "int", "byte", "short", "java.lang.Long", "java.lang.Integer", "java.lang.Byte",
				"java.lang.Short"));
	}

	private Map<String, TypeElement> tables = new HashMap<String, TypeElement>();
	private Map<String, TypeElement> subtables = new HashMap<String, TypeElement>();
	private FieldSorter fieldSorter;

	@Override
	public void processAnnotations(Set<? extends TypeElement> annotations, RoundEnvironment env) throws Exception {
		fieldSorter = new FieldSorter(logger);

		for (TypeElement annotation : annotations) {
			String annotationName = annotation.getQualifiedName().toString();
			if (annotationName.equals(Table.class.getCanonicalName())) {
				Set<? extends Element> elements = env.getElementsAnnotatedWith(annotation);
				processAnnotatedElements(elements);
			} else {
				logger.warn("Unexpected annotation: " + annotationName);
			}
		}
	}

	private void processAnnotatedElements(Set<? extends Element> elements) throws IOException {
		logger.info("Processing " + elements.size() + " elements...");

		URI uri = filer.getResource(StandardLocation.SOURCE_OUTPUT, "", "foo").toUri();
		File file = new File(uri);
		File sourcesPath = file.getParentFile();

		prepareTables(elements);

		for (Element element : elements) {
			if (element instanceof TypeElement) {
				TypeElement model = (TypeElement) element;
				String modelType = model.getQualifiedName().toString();

				List<VariableElement> fields = getFields(element);

				// sort the fields, due to unresolved bug in Eclipse APT
				fieldSorter.sortFields(fields, model, sourcesPath);

				// get the capitalized model name
				String entity = StringUtils.capitalize(model.getSimpleName().toString());

				logger.info("Generating code for entity '" + entity + "' with " + fields.size() + " columns...");

				/*********** Prepare the attributes for the templates ****************/

				/* Construct the list of columns */

				int index = 0;
				final List<Model> columns = new ArrayList<Model>();
				for (VariableElement field : fields) {
					String originalType = fieldType(field);
					String columnType = getColumnType(field);
					String fieldType = getAdjustedFieldType(field);
					String paramType = getParamType(field);
					String fieldName = field.getSimpleName().toString();

					boolean isSubtable = isSubtable(fieldType);
					String subtype = isSubtable ? getSubtableType(field) : null;

					Model column = new Model();
					column.put("name", fieldName);
					column.put("type", columnType);
					column.put("originalType", originalType);
					column.put("fieldType", fieldType);
					column.put("paramType", paramType);
					column.put("index", index++);
					column.put("isSubtable", isSubtable);
					column.put("subtype", subtype);

					columns.add(column);
				}

				/* Set the attributes */

				String packageName = calculatePackageName(model);

				boolean isNested = isSubtable(modelType);

				Map<String, Object> commonAttr = new HashMap<String, Object>();
				commonAttr.put("entity", entity);
				commonAttr.put("columns", columns);
				commonAttr.put("isNested", isNested);
				commonAttr.put("packageName", packageName);
				commonAttr.put("java_header", INFO_GENERATED);

				/*********** Generate the table class ****************/

				Model table = new Model();
				table.put("name", entity + "Table");
				table.putAll(commonAttr);

				/* Generate the "add" method in the table class */

				Model methodAdd = new Model();
				methodAdd.put("columns", columns);
				methodAdd.put("entity", entity);
				table.put("add", renderer.render("table_add.ftl", methodAdd));

				/* Generate the "insert" method in the table class */

				Model methodInsert = new Model();
				methodInsert.put("columns", columns);
				methodInsert.put("entity", entity);
				table.put("insert", renderer.render("table_insert.ftl", methodInsert));

				/* Generate the table class */

				String tableContent = renderer.render("table.ftl", table);
				writeToFile(packageName, entity + "Table.java", tableContent, model);

				/*********** Generate the cursor class ****************/

				Model cursor = new Model();
				cursor.put("name", entity);
				cursor.putAll(commonAttr);

				String cursorContent = renderer.render("cursor.ftl", cursor);
				writeToFile(packageName, entity + ".java", cursorContent, model);

				/*********** Generate the view class ****************/

				Model view = new Model();
				view.put("name", entity + "View");
				view.putAll(commonAttr);

				String viewContent = renderer.render("view.ftl", view);
				writeToFile(packageName, entity + "View.java", viewContent, model);

				/*********** Generate the query class ****************/

				Model query = new Model();
				query.put("name", entity + "Query");
				query.putAll(commonAttr);

				String queryContent = renderer.render("query.ftl", query);
				writeToFile(packageName, entity + "Query.java", queryContent, model);
			}
		}
	}

	private String calculatePackageName(TypeElement model) {
		Element parent = model.getEnclosingElement();
		while (parent != null && !(parent instanceof PackageElement)) {
			parent = parent.getEnclosingElement();
		}

		if (parent instanceof PackageElement) {
			PackageElement pkg = (PackageElement) parent;
			return pkg.getQualifiedName() + ".generated";
		} else {
			logger.error("Couldn't calculate the target package! Using default: " + DEFAULT_PACKAGE);
			return DEFAULT_PACKAGE;
		}
	}

	private List<VariableElement> getFields(Element element) {
		List<VariableElement> fields = new ArrayList<VariableElement>();

		for (Element enclosedElement : element.getEnclosedElements()) {
			if (enclosedElement.getKind().equals(ElementKind.FIELD)) {
				if (enclosedElement instanceof VariableElement) {
					VariableElement field = (VariableElement) enclosedElement;
					fields.add(field);
				}
			}
		}

		return fields;
	}

	private void prepareTables(Set<? extends Element> elements) {
		for (Element element : elements) {
			if (element instanceof TypeElement) {
				TypeElement model = (TypeElement) element;
				String name = model.getQualifiedName().toString();
				if (isReferencedBy(model, elements)) {
					logger.info("Detected subtable: " + name);
					subtables.put(name, model);
				} else {
					logger.info("Detected top-level table: " + name);
					tables.put(name, model);
				}
			}
		}
	}

	private boolean isReferencedBy(TypeElement model, Set<? extends Element> elements) {
		String modelType = model.getQualifiedName().toString();

		for (Element element : elements) {
			for (Element enclosedElement : element.getEnclosedElements()) {
				if (enclosedElement.getKind().equals(ElementKind.FIELD)) {
					if (enclosedElement instanceof VariableElement) {
						VariableElement field = (VariableElement) enclosedElement;
						TypeMirror fieldType = field.asType();
						if (fieldType.getKind().equals(TypeKind.DECLARED)) {
							Element typeAsElement = typeUtils.asElement(fieldType);
							if (typeAsElement instanceof TypeElement) {
								TypeElement typeElement = (TypeElement) typeAsElement;
								if (typeElement.getQualifiedName().toString().equals(modelType)) {
									return true;
								}
							}
						}
					}
				}
			}
		}

		return false;
	}

	private String getColumnType(VariableElement field) {
		String type = fieldType(field);

		String columnType;
		if (NUM_TYPES.contains(type)) {
			columnType = "Long";
		} else if ("boolean".equals(type) || "java.lang.Boolean".equals(type)) {
			columnType = "Boolean";
		} else if ("java.lang.String".equals(type)) {
			columnType = "String";
		} else if ("java.util.Date".equals(type)) {
			columnType = "Date";
		} else if ("byte[]".equals(type) || "java.nio.ByteBuffer".equals(type)) {
			columnType = "Binary";
		} else if (isSubtable(type)) {
			columnType = "Table";
		} else if ("java.lang.Object".equals(type)) {
			columnType = "Mixed";
		} else {
			columnType = "UNSUPPORTED_TYPE";
		}

		return columnType;
	}

	private boolean isSubtable(String type) {
		return subtables.containsKey(type);
	}

	private String fieldType(VariableElement field) {
		return field.asType().toString();
	}

	private String fieldSimpleType(VariableElement field) {
		return fieldType(field).replaceFirst("<.*>", "").replaceFirst(".*\\.", "");
	}

	private String getSubtableType(VariableElement field) {
		return StringUtils.capitalize(fieldSimpleType(field));
	}

	private String getAdjustedFieldType(VariableElement field) {
		String type = fieldType(field);

		if (NUM_TYPES.contains(type)) {
			type = "long";
		} else if (type.equals("byte[]")) {
			type = "java.nio.ByteBuffer";
		} else if (type.equals("java.lang.Object")) {
			type = "com.tightdb.Mixed";
		}

		return type;
	}

	private String getParamType(VariableElement field) {
		String type = fieldType(field);

		if (NUM_TYPES.contains(type)) {
			type = "long";
		} else if (type.equals("java.lang.Object")) {
			type = "com.tightdb.Mixed";
		}

		return type;
	}

}
