
//package com.bolinfest.closure;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.SourceFile;

import org.apache.commons.io.FilenameUtils;

public final class closure_run {

	public static List<File> getJSFiles() throws IOException {
		// Read js files from test_files
		File folderWithTest = null;
		String pathCurrFolder = new File(new File(".").getAbsolutePath()).getCanonicalPath();
		folderWithTest = new File(pathCurrFolder + "/test_files");

		List<File> listFiles = new ArrayList<File>();
		String js_ext = "js";
		if (folderWithTest.exists()) {
			for (final File fileEntry : folderWithTest.listFiles()) {

				if ((!fileEntry.isDirectory()) && (FilenameUtils.getExtension(fileEntry.getName()).equals(js_ext))) {
					listFiles.add(fileEntry);
				}
			}
		} else {
			System.out.println("Couldn't find the folder <test_files> in " + pathCurrFolder);
		}
		return listFiles;
	}

	/**
	 * @param code
	 *            JavaScript source code to compile.
	 * @return The compiled version of the code.
	 * @throws IOException
	 */
	public static String compile(File sourceFile, File resultFolder, FileWriter resultFile) throws IOException {

		Compiler compiler = new Compiler();
		compiler.ResultFolder = resultFolder;
		compiler.NameSourceFile = FilenameUtils.removeExtension(sourceFile.getName());

		compiler.ResultFile = resultFile;

		CompilerOptions options = new CompilerOptions();
		// Advanced mode is used here, but additional options could be
		// set, too.g
		CompilationLevel.ADVANCED_OPTIMIZATIONS.setOptionsForCompilationLevel(options);

		// To get the complete set of externs, the logic in
		// CompilerRunner.getDefaultExterns() should be used here.
		SourceFile extern = SourceFile.fromCode("externs.js", "function alert(x) {}");

		SourceFile input = SourceFile.fromFile(sourceFile);

		// compile() returns a Result, but it is not needed here.
		compiler.compile(extern, input, options);

		// The compiler is responsible for generating the compiled code;
		// it is not accessible via the Result.
		return compiler.toSource();
	}

	public static void main(String[] args) throws IOException {

		// Create a folder for results
		File resultFolder = new File(new File(new File(".").getAbsolutePath()).getCanonicalPath() + "/results");

		if (!resultFolder.exists()) {
			try {
				resultFolder.mkdir();
			} catch (SecurityException se) {
				System.out.println("The folder for results wasn't created");
			}
		}

		if (resultFolder.exists()) {
			FileWriter fileWriter;
			String pathResultFile = resultFolder + "/" + "working_time_passes.csv";
			File resultFile = new File(pathResultFile);
			if (!resultFile.exists()) {
				// Create a file
				resultFile.createNewFile();
			} else {
				// Clear file
				fileWriter = new FileWriter(pathResultFile);
				fileWriter.write("");
				fileWriter.close();
			}

			List<File> listFiles = getJSFiles();
			if (!listFiles.isEmpty()) {
				
				fileWriter = new FileWriter(pathResultFile);
				//Write the CSV file header
				fileWriter.append("FileName,PassName,Time,SizeBefore,SizeAfter");
				fileWriter.append("\n");
				
				for (File element : listFiles) {
					compile(element, resultFolder, fileWriter);
				}
				
				try {
					fileWriter.flush();
					fileWriter.close();
				} catch (IOException e) {
					System.out.println("Error while flushing/closing fileWriter !!!");
					e.printStackTrace();
				}
			}
		}
	}
}
