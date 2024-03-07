export default {
	collectCoverageFrom: [
		'src/main/resources/**/*.{ts,tsx}',
		// '!src/**/*.d.ts',
	],
	coverageProvider: 'v8',
	globals: {
		app: {
			name: 'com.enonic.app.sitemapxml',
			config: {}
		}
	},

	// moduleDirectories [array<string>]
	// Default: ["node_modules"]
	// An array of directory names to be searched recursively up from the
	// requiring module's location. Setting this option will override the
	// default, if you wish to still search node_modules for packages include it
	// along with any other options
	moduleDirectories: [
		'node_modules',
		'src/main/resources' // Does NOT fix: ModuleNotFoundError: Cannot find module '/guillotine/guillotine'
	],

	// moduleFileExtensions [array<string>]
	// Default: ["js", "mjs", "cjs", "jsx", "ts", "tsx", "json", "node"]
	// An array of file extensions your modules use. If you require modules
	// without specifying a file extension, these are the extensions Jest will
	// look for, in left-to-right order.
	// We recommend placing the extensions most commonly used in your project on
	// the left, so if you are using TypeScript, you may want to consider moving
	// "ts" and/or "tsx" to the beginning of the array.
	moduleFileExtensions: [
		'ts',
		'js'
	],

	// moduleNameMapper [object<string, string | array<string>>]
	// Default: null
	// A map from regular expressions to module names or to arrays of module
	// names that allow to stub out resources, like images or styles with a
	// single module.
	// Modules that are mapped to an alias are unmocked by default, regardless
	// of whether automocking is enabled or not.
	// Use <rootDir> string token to refer to rootDir value if you want to use
	// file paths.
	// Additionally, you can substitute captured regex groups using numbered
	// backreferences.
	moduleNameMapper: {
		'^/constants': '<rootDir>/src/main/resources/constants.ts',
		'^/guillotine/(.*)': '<rootDir>/src/main/resources/guillotine/$1', // DOES fix: ModuleNotFoundError: Cannot find module '/guillotine/guillotine'
		'^/lib/app-sitemapxml/(.*)': '<rootDir>/src/main/resources/lib/app-sitemapxml/$1',
	},

	// modulePathIgnorePatterns [array<string>]
	// Default: []
	// An array of regexp pattern strings that are matched against all module
	// paths before those paths are to be considered 'visible' to the module
	// loader. If a given module's path matches any of the patterns, it will not
	// be require()-able in the test environment.
	// These pattern strings match against the full path. Use the <rootDir>
	// string token to include the path to your project's root directory to
	// prevent it from accidentally ignoring all of your files in different
	// environments that may have different root directories.

	// modulePaths [array<string>]
	// Default: []
	// An alternative API to setting the NODE_PATH env variable, modulePaths is
	// an array of absolute paths to additional locations to search when
	// resolving modules. Use the <rootDir> string token to include the path to
	// your project's root directory.
	modulePaths: [
		'<rootDir>/src/main/resources' // Does NOT fix: ModuleNotFoundError: Cannot find module '/guillotine/guillotine'
	],

	preset: 'ts-jest/presets/js-with-babel-legacy',
	testEnvironment: 'node',
	testMatch: [
		'<rootDir>/test/**/*.(spec|test).{ts,tsx}'
	],
	transform: {
		'^.+\\.(js|jsx|ts|tsx)$': [
			'ts-jest',
			{
				tsconfig: './test/tsconfig.json',
			}
		]
	},
	transformIgnorePatterns: [
		'/node_modules/(?!(@enonic-types/guillotine)/)'
	],
}
