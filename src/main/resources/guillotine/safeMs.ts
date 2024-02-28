import ms from 'ms';


export function safeMs(number: number) {
	let result = 'N/A';
	try {
		result = ms(number);
	} catch (e) {
		// no-op
	} finally {
		return result;
	}
}
